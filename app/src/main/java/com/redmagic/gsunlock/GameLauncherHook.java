package com.redmagic.gsunlock;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * GameSpace (cn.nubia.gamelauncher) на LineageOS.
 *
 * 1) no-op на InputChannelWrapper.registerInputMonitor() — вендорный
 *    InputManager.myInput(String,Context) отсутствует во framework линейки
 *    (NoSuchMethodError в PowerPanelDetailsView.initView). Результат монитора
 *    кладётся в HashMap mReceiver и используется только в unRegister() через
 *    null-check, поэтому no-op безопасен.
 *
 * 2) То, что показывал PowerPanelService (окно 257x60) — это лишь виджет
 *    счётчика касаний (CPS/MPM), он питался от того самого глобального
 *    input-monitor, поэтому без данных и падал. Полноценная игровая панель
 *    с тумблерами — это GameControlDialog, которую в процессе поднимает
 *    GameControlDialogCtrl.showGameStrengthenModeView(pkg, activity, ...).
 *    Снаружи её не вызвать, поэтому регистрируем свой бродкаст-ресивер,
 *    который дёргает этот метод изнутри процесса.
 *
 *    Показать:  am broadcast -a com.redmagic.gsunlock.SHOW_PANEL --es pkg com.mobile.legends
 *    Скрыть:    am broadcast -a com.redmagic.gsunlock.HIDE_PANEL
 *               (или штатно: am broadcast -a cn.nubia.gamelauncher.action.close_controlpanel)
 */
public class GameLauncherHook implements IXposedHookLoadPackage {

    private static final String TAG = "[GSUnlock/GL] ";
    private static final String PKG = "cn.nubia.gamelauncher";
    private static final String CTRL_CLASS =
            "cn.nubia.gamelauncher.gamecontrolpanel.GameControlDialogCtrl";

    public static final String ACTION_SHOW = "com.redmagic.gsunlock.SHOW_PANEL";
    public static final String ACTION_HIDE = "com.redmagic.gsunlock.HIDE_PANEL";
    private static final String ACTION_NATIVE_CLOSE =
            "cn.nubia.gamelauncher.action.close_controlpanel";

    private static boolean sReceiverRegistered = false;
    private static Object sCtrl = null;
    private ClassLoader mCl;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName)) return;
        mCl = lpparam.classLoader;
        XposedBridge.log(TAG + "loaded " + PKG);

        // (1) no-op глобального input-monitor
        try {
            Class<?> wrapper = XposedHelpers.findClass(
                    "cn.nubia.systemwrapper.InputChannelWrapper", mCl);
            int n = XposedBridge.hookAllMethods(
                    wrapper, "registerInputMonitor",
                    XC_MethodReplacement.returnConstant(null)).size();
            XposedBridge.log(TAG + (n == 0
                    ? "WARNING: registerInputMonitor не найден"
                    : "registerInputMonitor no-op установлен, hooks=" + n));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "input-monitor hook failed: " + t);
        }

        // (1b) КОРЕНЬ ВСЕХ КРАШЕЙ: cn.nubia.common.util.FeatureUtil.get*(...)
        // делает Class.forName("com.zte.feature.Feature"), которого нет на Lineage.
        // На стоке исключение глушится; тут оно пробивает <clinit> FunctionAllocationHelper
        // (в статике вызываются getZteFeature*) -> ExceptionInInitializerError -> панель
        // не строится (showGameStrengthenModeView кидает InvocationTargetException).
        // Перехватываем ВСЕ методы FeatureUtil: НЕ зовём forName, возвращаем дефолт
        // (второй аргумент), а нужные фичи форсим. Чинит показ панели + включает
        // боковые перф-карточки частот ЦП/ГП (ключ *ZPERF_CUBE_GPSETTING*).
        try {
            Class<?> fu = XposedHelpers.findClass("cn.nubia.common.util.FeatureUtil", mCl);

            XposedBridge.hookAllMethods(fu, "getBoolean", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    String key = (p.args.length > 0 && p.args[0] != null) ? p.args[0].toString() : "";
                    boolean def = (p.args.length > 1 && p.args[1] instanceof Boolean)
                            ? ((Boolean) p.args[1]).booleanValue() : false;
                    boolean res = def;
                    if (key.contains("ZPERF_CUBE_GPSETTING")) res = true; // перф-карточки ЦП/ГП
                    p.setResult(Boolean.valueOf(res));
                }
            });

            XposedBridge.hookAllMethods(fu, "getInt", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    int def = (p.args.length > 1 && p.args[1] instanceof Integer)
                            ? ((Integer) p.args[1]).intValue() : 0;
                    p.setResult(Integer.valueOf(def));
                }
            });

            for (String mn : new String[]{"get", "getString"}) {
                XposedBridge.hookAllMethods(fu, mn, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        p.setResult(p.args.length > 1 ? p.args[1] : null);
                    }
                });
            }
            XposedBridge.log(TAG + "FeatureUtil нейтрализован (без forName), ZperfCube=true");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "FeatureUtil hook failed: " + t);
        }

        // (1c) Utils.isSupportFan() зовёт getPackageInfo("cn.nubia.fan") и НЕ ловит
        // NameNotFoundException -> на линейке (пакета нет) это валит
        // showGameStrengthenModeView -> InvocationTargetException -> панель не строится.
        // Кулер рулится отдельно (kernel-узел), поэтому говорим "вентилятор не поддерживается".
        try {
            Class<?> utils = XposedHelpers.findClass(
                    "cn.nubia.gamelauncher.gamecontrolpanel.utils.Utils", mCl);
            int n = XposedBridge.hookAllMethods(utils, "isSupportFan",
                    XC_MethodReplacement.returnConstant(Boolean.FALSE)).size();
            XposedBridge.log(TAG + "isSupportFan force=false, hooks=" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "isSupportFan hook failed: " + t);
        }

        // (2) регистрируем ресивер показа панели как только появится контекст
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            registerPanelReceiver((Context) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Application.onCreate hook failed: " + t);
        }
    }

    private void registerPanelReceiver(final Context ctx) {
        if (sReceiverRegistered || ctx == null) return;
        sReceiverRegistered = true;
        final Context appCtx = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        final Handler main = new Handler(Looper.getMainLooper());

        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, final Intent intent) {
                final String action = intent != null ? intent.getAction() : null;
                XposedBridge.log(TAG + "recv action=" + action);
                if (ACTION_SHOW.equals(action)) {
                    String pkg = intent.getStringExtra("pkg");
                    final String act = intent.getStringExtra("activity");
                    if (TextUtils.isEmpty(pkg)) {
                        try {
                            pkg = Settings.Global.getString(
                                    appCtx.getContentResolver(), "game_pack_name");
                        } catch (Throwable ignored) {}
                    }
                    final String gamePkg = TextUtils.isEmpty(pkg)
                            ? "com.mobile.legends" : pkg;
                    main.post(new Runnable() {
                        @Override public void run() { showPanel(appCtx, gamePkg, act); }
                    });
                } else if (ACTION_HIDE.equals(action)) {
                    main.post(new Runnable() {
                        @Override public void run() { hidePanel(appCtx); }
                    });
                }
            }
        };

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_SHOW);
        f.addAction(ACTION_HIDE);
        try {
            // RECEIVER_EXPORTED = 2 (нужно, т.к. шлём из shell/другого uid; Android 13+)
            appCtx.registerReceiver(r, f, 2);
            XposedBridge.log(TAG + "panel receiver зарегистрирован (SHOW/HIDE)");
        } catch (Throwable t) {
            try {
                appCtx.registerReceiver(r, f);
                XposedBridge.log(TAG + "panel receiver зарегистрирован (legacy)");
            } catch (Throwable t2) {
                XposedBridge.log(TAG + "registerReceiver failed: " + t2);
                sReceiverRegistered = false;
            }
        }
    }

    private Object getCtrl(Context ctx) {
        if (sCtrl != null) return sCtrl;
        try {
            Class<?> cls = XposedHelpers.findClass(CTRL_CLASS, mCl);
            // конструктор: GameControlDialogCtrl(Context)
            Constructor<?> best = null;
            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                Class<?>[] pt = ctor.getParameterTypes();
                if (pt.length == 1 && pt[0].isAssignableFrom(Context.class)) { best = ctor; break; }
                if (best == null && pt.length == 1) best = ctor;
            }
            if (best == null) { XposedBridge.log(TAG + "ctor(Context) не найден"); return null; }
            best.setAccessible(true);
            sCtrl = best.newInstance(ctx);
            XposedBridge.log(TAG + "GameControlDialogCtrl создан");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "getCtrl failed: " + t);
        }
        return sCtrl;
    }

    private void showPanel(Context ctx, String pkg, String activity) {
        try {
            Object ctrl = getCtrl(ctx);
            if (ctrl == null) return;
            // in-panel секция "custom performance" зависит от ZTE MindSyncManager (нет на линейке):
            // без этого getApplyProfile() бросает NoClassDefFoundError и валит всю панель.
            // Гейт SUPPORT_CUSTOM_PERFORMANCE_MODE=false -> блок initCustomPerf пропускается.
            try {
                Class<?> gspv = XposedHelpers.findClass(
                    "cn.nubia.gamelauncher.gamecontrolpanel.GameStrengthenPerformanceView", mCl);
                XposedHelpers.setStaticObjectField(gspv, "SUPPORT_CUSTOM_PERFORMANCE_MODE", Boolean.FALSE);
                XposedBridge.log(TAG + "SUPPORT_CUSTOM_PERFORMANCE_MODE=false (MindSync недоступен)");
            } catch (Throwable tt) {
                XposedBridge.log(TAG + "setSupportCustomPerf failed: " + tt);
            }
            Method show = null;
            for (Method m : ctrl.getClass().getDeclaredMethods()) {
                if (m.getName().equals("showGameStrengthenModeView")) { show = m; break; }
            }
            if (show == null) { XposedBridge.log(TAG + "showGameStrengthenModeView не найден"); return; }
            Class<?>[] pt = show.getParameterTypes();
            Object[] args = new Object[pt.length];
            int stringIdx = 0;
            for (int i = 0; i < pt.length; i++) {
                if (pt[i] == String.class) {
                    args[i] = (stringIdx++ == 0) ? pkg : activity; // 1-й String=pkg, 2-й=activity
                } else if (pt[i] == boolean.class) {
                    args[i] = Boolean.FALSE;
                } else if (pt[i] == int.class) {
                    args[i] = Integer.valueOf(0);
                } else {
                    args[i] = null;
                }
            }
            show.setAccessible(true);
            show.invoke(ctrl, args);
            XposedBridge.log(TAG + "showGameStrengthenModeView(" + pkg + ") вызван, args=" + pt.length);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "showPanel failed: " + t);
            // распаковываем InvocationTargetException -> печатаем настоящую причину + стек
            Throwable c = t;
            while (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) {
                c = c.getCause();
            }
            XposedBridge.log(TAG + "  ROOT CAUSE: " + c);
            for (StackTraceElement e : c.getStackTrace()) {
                XposedBridge.log(TAG + "    at " + e);
            }
            Throwable cz = c.getCause();
            if (cz != null) {
                XposedBridge.log(TAG + "  Caused by: " + cz);
                for (StackTraceElement e : cz.getStackTrace()) {
                    XposedBridge.log(TAG + "    at " + e);
                }
            }
        }
    }

    private void hidePanel(Context ctx) {
        // штатное закрытие — собственный ресивер контроллера ловит этот action
        try {
            ctx.sendBroadcast(new Intent(ACTION_NATIVE_CLOSE));
            XposedBridge.log(TAG + "послан " + ACTION_NATIVE_CLOSE);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hidePanel failed: " + t);
        }
    }
}
