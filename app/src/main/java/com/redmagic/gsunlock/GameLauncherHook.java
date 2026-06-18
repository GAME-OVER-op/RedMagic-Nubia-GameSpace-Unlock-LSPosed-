package com.redmagic.gsunlock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * GameSpace (cn.nubia.gamelauncher) на LineageOS: боковая панель PowerPanelDetailsView
 * падает в initView() при регистрации глобального input-monitor через вендорный
 * InputManager.myInput(String, Context) — этого метода нет во framework линейки
 * (NoSuchMethodError -> BadToken раньше, теперь NoSuchMethodError в initView).
 *
 * Результат InputChannelWrapper.registerInputMonitor() кладётся в HashMap mReceiver
 * и используется ТОЛЬКО в unRegister() через null-проверку (if r != null r.dispose()),
 * поэтому no-op безопасен: HashMap остаётся пуст, unRegister пропускает dispose,
 * initView проходит дальше -> initLayoutParams добавляет окно TYPE_APPLICATION_OVERLAY (2038).
 *
 * Глобальный monitor нужен был только для свайпа-вызова панели поверх игры;
 * мы вызываем панель триггером F7/F8, так что он нам не нужен.
 */
public class GameLauncherHook implements IXposedHookLoadPackage {

    private static final String TAG = "[GSUnlock/GL] ";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!"cn.nubia.gamelauncher".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + "loaded cn.nubia.gamelauncher");
        try {
            Class<?> wrapper = XposedHelpers.findClass(
                    "cn.nubia.systemwrapper.InputChannelWrapper", lpparam.classLoader);
            int n = XposedBridge.hookAllMethods(
                    wrapper, "registerInputMonitor",
                    XC_MethodReplacement.returnConstant(null)).size();
            if (n == 0) {
                XposedBridge.log(TAG + "WARNING: registerInputMonitor не найден");
            } else {
                XposedBridge.log(TAG + "registerInputMonitor no-op установлен, hooks=" + n);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook failed: " + t);
        }
    }
}
