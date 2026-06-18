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

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    // Живой командный канал: модуль грузится раз, дальше всё через broadcast без пересборки.
    public static final String ACTION_CMD = "com.redmagic.gsunlock.CMD";

    private static boolean sReceiverRegistered = false;
    private static Object sCtrl = null;
    private ClassLoader mCl;
    // храним живые хуки по ключу "cls#method", чтобы снимать через CMD unhook
    private static final Map<String, Set<XC_MethodHook.Unhook>> sHooks = new HashMap<>();

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

        // (1d) РАСКЛАДКА: боковые карточки vs нижняя полоса.
        // GameControlDialog.initView() и adjustZteRotation() включают широкую
        // landscape-раскладку (addOnLayoutChangeListener + форс orientation=landscape
        // + ресайз rootView) ТОЛЬКО когда Utils.isW210DS()==true. Этот метод читает
        // SystemProperties.get("ro.product.name").contains("W210DS"). На NX769J это
        // false -> панель сваливается в дефолтную (компактную нижнюю) раскладку.
        // Форсим true -> панель раскладывается боковыми карточками по краям экрана.
        try {
            Class<?> utilsW = XposedHelpers.findClass(
                    "cn.nubia.gamelauncher.gamecontrolpanel.utils.Utils", mCl);
            int nw = XposedBridge.hookAllMethods(utilsW, "isW210DS",
                    XC_MethodReplacement.returnConstant(Boolean.TRUE)).size();
            XposedBridge.log(TAG + "isW210DS force=true (боковая раскладка), hooks=" + nw);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "isW210DS hook failed: " + t);
        }

        // (1e) MindSync отсутствует на линейке: CustomPerfProfileManager.getApplyProfile()
        // внутри трогает com.zte.performance.mindsync.MindSyncManager$Trigger ->
        // NoClassDefFoundError, и это валит процесс при perModeChange (вызов до
        // проверки mode==4). Возвращаем null — тело с MindSync не выполняется,
        // а perModeChange использует результат только в логе.
        try {
            Class<?> ppm = XposedHelpers.findClass(
                    "cn.nubia.gamelauncher.gamecontrolpanel.performancetuning.CustomPerfProfileManager", mCl);
            int np = XposedBridge.hookAllMethods(ppm, "getApplyProfile",
                    XC_MethodReplacement.returnConstant(null)).size();
            XposedBridge.log(TAG + "getApplyProfile force=null (MindSync обойдён), hooks=" + np);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "getApplyProfile hook failed: " + t);
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
                } else if (ACTION_CMD.equals(action)) {
                    main.post(new Runnable() {
                        @Override public void run() { handleCmd(appCtx, intent); }
                    });
                }
            }
        };

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_SHOW);
        f.addAction(ACTION_HIDE);
        f.addAction(ACTION_CMD);
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

    // ==================== ЖИВОЙ КОМАНДНЫЙ КАНАЛ ====================
    // am broadcast -a com.redmagic.gsunlock.CMD -p cn.nubia.gamelauncher --es op <OP> ...
    //  hook    --es cls FQCN --es m method [--es ret <typed>]   поставить returnConstant-хук
    //  unhook  --es key cls#method                              снять хук
    //  istatic --es cls FQCN --es m method [--es a0..a5 <typed>] вызвать static-метод
    //  icall   --es m method [--es a0..a5 <typed>]              вызвать метод на контроллере (sCtrl)
    //  sget    --es cls FQCN --es f field                       прочитать static-поле
    //  sset    --es cls FQCN --es f field --es v <typed>        записать static-поле
    //  set     --es scope <global|system|secure> --es key K --es v V   записать Settings
    //  dump    [--es filter substr]                             дамп дерева View всех окон
    // typed: s:STR | i:N | l:N | f:N | b:true | null | (без префикса = строка)
    private void handleCmd(final Context ctx, final Intent it) {
        final String op = it.getStringExtra("op");
        if (op == null) { XposedBridge.log(TAG + "CMD: нет op"); return; }
        try {
            if ("hook".equals(op)) {
                String cls = it.getStringExtra("cls");
                String m = it.getStringExtra("m");
                Object ret = coerce(it.getStringExtra("ret"));
                Class<?> c = XposedHelpers.findClass(cls, mCl);
                Set<XC_MethodHook.Unhook> set =
                        XposedBridge.hookAllMethods(c, m, XC_MethodReplacement.returnConstant(ret));
                sHooks.put(cls + "#" + m, set);
                XposedBridge.log(TAG + "CMD hook " + cls + "#" + m + " -> " + ret + " (" + set.size() + ")");
            } else if ("unhook".equals(op)) {
                String key = it.getStringExtra("key");
                Set<XC_MethodHook.Unhook> set = sHooks.remove(key);
                int n = 0;
                if (set != null) { for (XC_MethodHook.Unhook u : set) { u.unhook(); n++; } }
                XposedBridge.log(TAG + "CMD unhook " + key + " (" + n + ")");
            } else if ("istatic".equals(op)) {
                Class<?> c = XposedHelpers.findClass(it.getStringExtra("cls"), mCl);
                Object r = XposedHelpers.callStaticMethod(c, it.getStringExtra("m"), readArgs(it));
                XposedBridge.log(TAG + "CMD istatic " + it.getStringExtra("m") + " = " + r);
            } else if ("icall".equals(op)) {
                Object target = (sCtrl != null) ? sCtrl : getCtrl(ctx);
                if (target == null) { XposedBridge.log(TAG + "CMD icall: нет ctrl"); return; }
                Object r = XposedHelpers.callMethod(target, it.getStringExtra("m"), readArgs(it));
                XposedBridge.log(TAG + "CMD icall " + it.getStringExtra("m") + " = " + r);
            } else if ("sget".equals(op)) {
                Class<?> c = XposedHelpers.findClass(it.getStringExtra("cls"), mCl);
                Object r = XposedHelpers.getStaticObjectField(c, it.getStringExtra("f"));
                XposedBridge.log(TAG + "CMD sget " + it.getStringExtra("f") + " = " + r);
            } else if ("sset".equals(op)) {
                Class<?> c = XposedHelpers.findClass(it.getStringExtra("cls"), mCl);
                XposedHelpers.setStaticObjectField(c, it.getStringExtra("f"), coerce(it.getStringExtra("v")));
                XposedBridge.log(TAG + "CMD sset " + it.getStringExtra("f") + " <- " + it.getStringExtra("v"));
            } else if ("set".equals(op)) {
                String scope = it.getStringExtra("scope");
                String key = it.getStringExtra("key");
                String v = it.getStringExtra("v");
                if ("system".equals(scope)) Settings.System.putString(ctx.getContentResolver(), key, v);
                else if ("secure".equals(scope)) Settings.Secure.putString(ctx.getContentResolver(), key, v);
                else Settings.Global.putString(ctx.getContentResolver(), key, v);
                XposedBridge.log(TAG + "CMD set " + scope + " " + key + " = " + v);
            } else if ("dump".equals(op)) {
                dumpAllViews(it.getStringExtra("filter"));
            } else {
                XposedBridge.log(TAG + "CMD: неизвестный op=" + op);
            }
        } catch (Throwable t) {
            Throwable cse = t;
            while (cse instanceof java.lang.reflect.InvocationTargetException && cse.getCause() != null) cse = cse.getCause();
            XposedBridge.log(TAG + "CMD " + op + " FAILED: " + cse);
        }
    }

    private Object[] readArgs(Intent it) {
        List<Object> a = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String raw = it.getStringExtra("a" + i);
            if (raw == null) break;
            a.add(coerce(raw));
        }
        return a.toArray();
    }

    private Object coerce(String raw) {
        if (raw == null || "null".equals(raw)) return null;
        if (raw.startsWith("s:")) return raw.substring(2);
        if (raw.startsWith("i:")) return Integer.valueOf(Integer.parseInt(raw.substring(2)));
        if (raw.startsWith("l:")) return Long.valueOf(Long.parseLong(raw.substring(2)));
        if (raw.startsWith("f:")) return Float.valueOf(Float.parseFloat(raw.substring(2)));
        if (raw.startsWith("b:")) return Boolean.valueOf("true".equalsIgnoreCase(raw.substring(2)));
        if ("true".equals(raw)) return Boolean.TRUE;
        if ("false".equals(raw)) return Boolean.FALSE;
        return raw;
    }

    @SuppressWarnings("unchecked")
    private void dumpAllViews(String filter) {
        try {
            Class<?> wmg = XposedHelpers.findClass("android.view.WindowManagerGlobal", mCl);
            Object inst = XposedHelpers.callStaticMethod(wmg, "getInstance");
            Object viewsObj = XposedHelpers.getObjectField(inst, "mViews");
            List<View> views = new ArrayList<>();
            if (viewsObj instanceof List) {
                for (Object o : (List<Object>) viewsObj) views.add((View) o);
            } else if (viewsObj instanceof View[]) {
                for (View o : (View[]) viewsObj) views.add(o);
            }
            XposedBridge.log(TAG + "DUMP: окон=" + views.size() + (filter != null ? " filter=" + filter : ""));
            for (View root : views) {
                XposedBridge.log(TAG + "== root " + root.getClass().getName()
                        + " " + root.getWidth() + "x" + root.getHeight() + " ==");
                dumpViewTree(root, 0, filter);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "dump failed: " + t);
        }
    }

    private void dumpViewTree(View v, int depth, String filter) {
        if (v == null || depth > 12) return;
        String id = "";
        try {
            if (v.getId() != View.NO_ID) id = v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable ignored) {}
        String vis = v.getVisibility() == View.VISIBLE ? "VIS"
                : (v.getVisibility() == View.GONE ? "GONE" : "INV");
        String line = v.getClass().getSimpleName() + " id=" + id + " " + vis
                + " " + v.getWidth() + "x" + v.getHeight()
                + " @" + ((int) v.getX()) + "," + ((int) v.getY());
        boolean show = filter == null
                || v.getClass().getName().toLowerCase().contains(filter.toLowerCase())
                || id.toLowerCase().contains(filter.toLowerCase());
        if (show) {
            StringBuilder pad = new StringBuilder();
            for (int i = 0; i < depth; i++) pad.append("  ");
            XposedBridge.log(TAG + "  " + pad + line);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpViewTree(g.getChildAt(i), depth + 1, filter);
        }
    }
}
