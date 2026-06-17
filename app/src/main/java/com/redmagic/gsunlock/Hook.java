package com.redmagic.gsunlock;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
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
                break;
            case "cn.nubia.gameassist":
                forceBooleanTrue(lpparam.classLoader,
                        "com.zte.gameassist.config.ZteFeature",
                        new String[]{
                                "isRedMagicProduct",
                                "isRedMagic",
                                "isSupport"
                        });
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
}
