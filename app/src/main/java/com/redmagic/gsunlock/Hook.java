package com.redmagic.gsunlock;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * RedMagic / Nubia GameSpace unlock for LineageOS.
 *
 * На стоке аппы зовут com.zte.feature.Feature (из BOOTCLASSPATH) чтобы
 * понять "это RedMagic OS / RedMagic-устройство?". На Lineage этого класса нет,
 * проверка падает в ClassNotFoundException и возвращает false ->
 * GameCounterService делает "Exit game scene", а GameAssist падает в onCreate.
 *
 * Мы заменяем эти методы на return true — их тело (с обращением к
 * отсутствующему классу) вообще не выполняется.
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "[GSUnlock] ";

    // android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY (@hide)
    private static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000;
    // android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private static final int TYPE_APPLICATION_OVERLAY = 2038;
    // RECEIVER_EXPORTED (Android 13+) чтобы принимать adb am broadcast из другого uid
    private static final int RECEIVER_EXPORTED = 0x2;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        switch (lpparam.packageName) {
            case "cn.zte.gamefloat":
                forceBooleanTrue(lpparam.classLoader,
                        "cn.zte.gamefloat.util.ConfigUtil",
                        new String[]{
                                "isRedMagicRunOnMyOs",
                                "isRedMagic",
                                "isRunOnMyOs",
                                "isMyOs"
                        });
                // КОРЕНЬ краша боковых панелей: WindowManagerWrapper ставит на окно
                // privateFlags |= PRIVATE_FLAG_TRUSTED_OVERLAY (0x20000000). Добавление
                // trusted-overlay требует android.permission.INTERNAL_SYSTEM_WINDOW
                // (signature|recents|module), которого у uid аппа нет на Lineage ->
                // SecurityException в DisplayPolicy.validateAddingWindowLw -> addView падает.
                // Снимаем этот бит у ЛЮБОГО окна прямо перед addView, в процессе аппа,
                // до IPC в WindowManagerService. Тип окна 2038 (TYPE_APPLICATION_OVERLAY)
                // + appop SYSTEM_ALERT_WINDOW этого права не требуют.
                stripTrustedOverlay(lpparam.classLoader);
                // PowerPanelDetailsView.initView -> InputChannelWrapper.registerInputMonitor
                // -> InputChannelCompat.initInputMonitor -> InputManager.myInput(String,Context)
                // (приватный метод ZTE-фреймворка, отсутствует на Android 16) -> NoSuchMethodError.
                // Глушим регистрацию input-monitor: карточка отрисуется, теряется лишь
                // свайп-вне-окна для закрытия (не критично).
                neutralizeInputMonitor(lpparam.classLoader);
                break;
            case "cn.nubia.gameassist":
                // САМОЕ ПЕРВОЕ: подсунуть отсутствующий на кастоме класс
                // com.zte.feature.Feature (заглушку из нашего модуля) в класслоадер
                // аппа. Без него ZteFeatureWrapper.<clinit> -> SystemMgr.<clinit> ->
                // весь борд падают с NoClassDefFoundError (краш в onCreate стр.101
                // уже ПОСЛЕ swallowExceptions). Должно стоять до загрузки классов аппа.
                injectFeatureStub(lpparam.classLoader);
                forceBooleanTrue(lpparam.classLoader,
                        "com.zte.gameassist.config.ZteFeature",
                        new String[]{
                                "isRedMagicProduct",
                                "isRedMagic",
                                "isSupport"
                        });
                // КОРЕНЬ текущего краша: GameAssistApplication.onCreate -> loadComponent ->
                // Router.registerComponent("projection",ctx) -> ProjectionComApplication.onCreate ->
                // ZteFeatureWrapper.<clinit> дергает com.zte.feature.Feature (класс ZTE-фреймворка,
                // отсутствует на кастоме) -> NoClassDefFoundError рушит весь процесс.
                // Оборачиваем static Router.registerComponent в try/catch: компонент, которому
                // не хватает ZTE-классов, просто пропускается, а остальные (в т.ч. сам борд) грузятся.
                swallowExceptions(lpparam.classLoader,
                        "cn.nubia.componentcenter.router.Router", "registerComponent");
                // Окно борда создаётся с type=2027 (системное, >2000 -> нужен INTERNAL_SYSTEM_WINDOW,
                // которого у непlatform-аппа нет -> BadTokenException). Перед addView в процессе аппа
                // переписываем тип в 2038 (TYPE_APPLICATION_OVERLAY) и снимаем trusted-overlay.
                forceApplicationOverlay(lpparam.classLoader);
                // FullScreenInputMonitor.registerInputEventListener/pilferPointers требуют MONITOR_INPUT
                // (signature-only, не выдаётся непlatform-аппу) -> глушим: свайп-триггер не нужен,
                // борд показываем своим broadcast'ом.
                neutralizeGameAssistInput(lpparam.classLoader);
                // Внешний триггер показа/скрытия борда без свайпа:
                // adb shell am broadcast -a com.redmagic.gsunlock.SHOW_PANEL
                installPanelTrigger(lpparam.classLoader);
                break;
            case "cn.nubia.gamelauncher":
                // тот же WindowManagerWrapper и здесь — большая панель (GameControlDialog/
                // PowerPanel/Strengthen-вью) тоже ставит trusted-overlay -> снимаем так же.
                stripTrustedOverlay(lpparam.classLoader);
                break;
            default:
                break;
        }
    }

    private void forceBooleanTrue(ClassLoader cl, String className, String[] names) {
        Class<?> clazz;
        try {
            clazz = XposedHelpers.findClass(className, cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "class not found: " + className + " (" + t + ")");
            return;
        }
        int hooked = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            Class<?> rt = m.getReturnType();
            boolean isBool = (rt == boolean.class) || (rt == Boolean.class);
            if (!isBool) continue;
            for (String name : names) {
                if (m.getName().equals(name)) {
                    try {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true));
                        hooked++;
                        XposedBridge.log(TAG + "hooked " + className + "." + m.getName()
                                + " (" + m.getParameterTypes().length + " args) -> true");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "hook fail " + m.getName() + ": " + t);
                    }
                }
            }
        }
        if (hooked == 0) {
            XposedBridge.log(TAG + "WARNING: no methods hooked in " + className
                    + " — проверь имена методов");
        }
    }

    /**
     * Снимает PRIVATE_FLAG_TRUSTED_OVERLAY со всех окон, добавляемых аппом.
     * Хукаем все перегрузки WindowManagerImpl.addView(...) и чистим privateFlags
     * у любого аргумента типа WindowManager.LayoutParams перед вызовом оригинала.
     */
    private void stripTrustedOverlay(ClassLoader cl) {
        try {
            Class<?> wmImpl = XposedHelpers.findClass("android.view.WindowManagerImpl", cl);
            int n = XposedBridge.hookAllMethods(wmImpl, "addView", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null) return;
                    for (Object a : param.args) {
                        if (a instanceof android.view.WindowManager.LayoutParams) {
                            try {
                                int pf = XposedHelpers.getIntField(a, "privateFlags");
                                if ((pf & PRIVATE_FLAG_TRUSTED_OVERLAY) != 0) {
                                    pf &= ~PRIVATE_FLAG_TRUSTED_OVERLAY;
                                    XposedHelpers.setIntField(a, "privateFlags", pf);
                                    XposedBridge.log(TAG + "stripped TRUSTED_OVERLAY before addView");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "strip fail: " + t);
                            }
                        }
                    }
                }
            }).size();
            XposedBridge.log(TAG + "addView hook установлен (cn.zte.gamefloat), hooks=" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "stripTrustedOverlay failed: " + t);
        }
    }

    /**
     * Делает cn.zte.gamefloat.system.InputChannelWrapper.registerInputMonitor(...) no-op.
     * Оригинал зовёт приватный ZTE-метод InputManager.myInput(String,Context), которого
     * нет на стоковом Android 16 -> NoSuchMethodError рушит PowerPanelDetailsView.
     */
    private void neutralizeInputMonitor(ClassLoader cl) {
        try {
            Class<?> wrapper = XposedHelpers.findClass(
                    "cn.zte.gamefloat.system.InputChannelWrapper", cl);
            int n = XposedBridge.hookAllMethods(wrapper, "registerInputMonitor",
                    XC_MethodReplacement.returnConstant(null)).size();
            XposedBridge.log(TAG + "registerInputMonitor нейтрализован (cn.zte.gamefloat), hooks=" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "neutralizeInputMonitor failed: " + t);
        }
    }

    /**
     * Оборачивает все перегрузки className.methodName в try/catch: оригинал вызывается,
     * но любое выброшенное Throwable проглатывается (метод возвращает null/void).
     * Используется чтобы падение одного компонента в Router.registerComponent не убивало
     * весь процесс GameAssist (на кастоме нет части ZTE-фреймворк-классов).
     */
    private void swallowExceptions(ClassLoader cl, final String className, final String methodName) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            int n = XposedBridge.hookAllMethods(c, methodName, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam p) {
                    try {
                        return XposedBridge.invokeOriginalMethod(p.method, p.thisObject, p.args);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "swallowed " + className + "." + methodName
                                + ": " + t.getCause() + " / " + t);
                        return null;
                    }
                }
            }).size();
            XposedBridge.log(TAG + "swallowExceptions wrap " + className + "." + methodName
                    + " hooks=" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "swallowExceptions find fail " + className + ": " + t);
        }
    }

    /**
     * Перед WindowManagerImpl.addView переписывает системный тип окна (2000..2999, кроме 2038)
     * в 2038 (TYPE_APPLICATION_OVERLAY) и снимает PRIVATE_FLAG_TRUSTED_OVERLAY.
     * Так борд GameAssist (исходно type=2027) добавляется как обычный overlay-аппа без
     * INTERNAL_SYSTEM_WINDOW. Нужен лишь appop SYSTEM_ALERT_WINDOW.
     */
    private void forceApplicationOverlay(ClassLoader cl) {
        try {
            Class<?> wmImpl = XposedHelpers.findClass("android.view.WindowManagerImpl", cl);
            int n = XposedBridge.hookAllMethods(wmImpl, "addView", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null) return;
                    for (Object a : param.args) {
                        if (a instanceof android.view.WindowManager.LayoutParams) {
                            android.view.WindowManager.LayoutParams lp =
                                    (android.view.WindowManager.LayoutParams) a;
                            try {
                                if (lp.type >= 2000 && lp.type <= 2999
                                        && lp.type != TYPE_APPLICATION_OVERLAY) {
                                    XposedBridge.log(TAG + "window type " + lp.type + " -> 2038");
                                    lp.type = TYPE_APPLICATION_OVERLAY;
                                }
                                int pf = XposedHelpers.getIntField(lp, "privateFlags");
                                if ((pf & PRIVATE_FLAG_TRUSTED_OVERLAY) != 0) {
                                    pf &= ~PRIVATE_FLAG_TRUSTED_OVERLAY;
                                    XposedHelpers.setIntField(lp, "privateFlags", pf);
                                    XposedBridge.log(TAG + "stripped TRUSTED_OVERLAY (gameassist)");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "overlay fix fail: " + t);
                            }
                        }
                    }
                }
            }).size();
            XposedBridge.log(TAG + "forceApplicationOverlay addView hooks=" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "forceApplicationOverlay failed: " + t);
        }
    }

    /**
     * Глушит cn.nubia.gameassist.input.FullScreenInputMonitor.registerInputEventListener /
     * pilferPointers — оба требуют android.permission.MONITOR_INPUT (signature-only),
     * которого у непlatform-аппа нет. Свайп-открытие борда теряется (его заменяет broadcast),
     * сам борд при этом строится и показывается нормально.
     */
    private void neutralizeGameAssistInput(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "cn.nubia.gameassist.input.FullScreenInputMonitor", cl);
            int n = 0;
            n += XposedBridge.hookAllMethods(c, "registerInputEventListener",
                    XC_MethodReplacement.returnConstant(null)).size();
            n += XposedBridge.hookAllMethods(c, "pilferPointers",
                    XC_MethodReplacement.returnConstant(null)).size();
            XposedBridge.log(TAG + "FullScreenInputMonitor нейтрализован, hooks=" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "neutralizeGameAssistInput failed: " + t);
        }
    }

    /**
     * После GameAssistApplication.onCreate регистрирует receiver на
     *   com.redmagic.gsunlock.SHOW_PANEL / HIDE_PANEL
     * и зовёт GameAssistWindowManager.getInstance(ctx).showWindow("hook") / hideWindow("hook").
     * Это даёт ручной показ борда без свайпа:
     *   adb shell am broadcast -a com.redmagic.gsunlock.SHOW_PANEL
     */
    /**
     * На кастомной прошивке нет класса com.zte.feature.Feature (ZTE framework,
     * обычно в BOOTCLASSPATH). Его статически дергают ZteFeatureWrapper.<clinit>,
     * DisplayManagerWrapper и Constants. Отсутствие -> NoClassDefFoundError при
     * инициализации классов отравляет ZteFeatureWrapper -> SystemMgr -> весь борд.
     *
     * Лечим причину: добавляем dex нашего модуля (в нём лежит заглушка
     * com.zte.feature.Feature) в DexPathList класслоадера приложения. Тогда
     * нативный резолвер ART находит класс, и вся цепочка инициализируется со
     * значениями по умолчанию (ZTE_FEATURE_MAGIC_GAME_ASSIST=true -> ENABLE_GAME_ASSIST
     * включён, остальные RM-фичи выкл). Доп. страховка — хук ClassLoader.loadClass.
     */
    private void injectFeatureStub(ClassLoader appCl) {
        // Класс заглушки грузится НАШИМ загрузчиком ДО установки хука loadClass,
        // чтобы не словить рекурсию при резолве этого же имени.
        final Class<?> stub = com.zte.feature.Feature.class;
        ClassLoader moduleCl = Hook.class.getClassLoader();

        // 1) ОСНОВНОЙ способ: домержить dexElements модуля в DexPathList аппа.
        try {
            Object appPathList = XposedHelpers.getObjectField(appCl, "pathList");
            Object modPathList = XposedHelpers.getObjectField(moduleCl, "pathList");
            Object appElements = XposedHelpers.getObjectField(appPathList, "dexElements");
            Object modElements = XposedHelpers.getObjectField(modPathList, "dexElements");
            int al = Array.getLength(appElements);
            int ml = Array.getLength(modElements);
            Class<?> elemType = appElements.getClass().getComponentType();
            Object merged = Array.newInstance(elemType, al + ml);
            // dex аппа первыми -> его собственные классы остаются в приоритете,
            // наш модуль лишь добавляет недостающие (com.zte.feature.Feature).
            System.arraycopy(appElements, 0, merged, 0, al);
            System.arraycopy(modElements, 0, merged, al, ml);
            XposedHelpers.setObjectField(appPathList, "dexElements", merged);
            XposedBridge.log(TAG + "injectFeatureStub: dexElements аппа=" + al
                    + " + модуль=" + ml + " домержено");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "injectFeatureStub dex-merge failed: " + t);
        }

        // 2) СТРАХОВКА: если ART для нестандартной цепочки дернёт Java loadClass —
        //    отдаём заглушку напрямую по точному имени.
        try {
            final String target = "com.zte.feature.Feature";
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length >= 1
                            && target.equals(param.args[0])) {
                        param.setResult(stub);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "injectFeatureStub loadClass-hook failed: " + t);
        }

        // 3) Проверка: класс теперь должен резолвиться загрузчиком аппа.
        try {
            XposedHelpers.findClass("com.zte.feature.Feature", appCl);
            XposedBridge.log(TAG + "injectFeatureStub: com.zte.feature.Feature OK (резолвится в аппе)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "injectFeatureStub verify FAILED, Feature всё ещё не виден: " + t);
        }
    }

    private void installPanelTrigger(ClassLoader cl) {
        try {
            Class<?> appClass = XposedHelpers.findClass(
                    "cn.nubia.gameassist.GameAssistApplication", cl);
            XposedBridge.hookAllMethods(appClass, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final android.content.Context ctx =
                                (android.content.Context) param.thisObject;
                        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                            @Override
                            public void onReceive(android.content.Context c, android.content.Intent i) {
                                try {
                                    Class<?> wm = XposedHelpers.findClass(
                                            "cn.nubia.gameassist.panel.GameAssistWindowManager",
                                            ctx.getClassLoader());
                                    Object inst = XposedHelpers.callStaticMethod(wm, "getInstance", ctx);
                                    String act = i.getAction();
                                    if (act != null && act.endsWith("HIDE_PANEL")) {
                                        XposedHelpers.callMethod(inst, "hideWindow", "hook");
                                    } else {
                                        XposedHelpers.callMethod(inst, "showWindow", "hook");
                                    }
                                    XposedBridge.log(TAG + "panel trigger fired: " + act);
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + "panel trigger invoke fail: " + t);
                                }
                            }
                        };
                        android.content.IntentFilter f = new android.content.IntentFilter();
                        f.addAction("com.redmagic.gsunlock.SHOW_PANEL");
                        f.addAction("com.redmagic.gsunlock.HIDE_PANEL");
                        ctx.registerReceiver(r, f, RECEIVER_EXPORTED);
                        XposedBridge.log(TAG + "panel trigger receiver registered");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "installPanelTrigger register fail: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + "installPanelTrigger hooked GameAssistApplication.onCreate");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "installPanelTrigger failed: " + t);
        }
    }
}
