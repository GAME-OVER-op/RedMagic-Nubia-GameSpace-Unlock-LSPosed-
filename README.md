# RedMagic / Nubia GameSpace Unlock (LSPosed)

Обходит проверки `com.zte.feature.Feature`, из-за которых RedMagic Game Space
не работает на LineageOS (класс живёт в BOOTCLASSPATH стока и на Lineage отсутствует).

## Что делает
- `cn.zte.gamefloat` → `ConfigUtil.isRedMagicRunOnMyOs()` → `true`
- `cn.nubia.gameassist` → `ZteFeature.isRedMagicProduct()` → `true`

## Сборка (GitHub Actions)
1. Создай пустой репозиторий на GitHub.
2. Залей туда содержимое этого архива (корень репо = папка с `settings.gradle`).
3. Вкладка **Actions** → workflow запустится сам (или Run workflow).
4. Скачай артефакт **gsunlock-debug-apk** → внутри `app-debug.apk`.

## Установка
1. Установи `app-debug.apk`.
2. В LSPosed включи модуль, scope: `cn.zte.gamefloat`, `cn.nubia.gameassist`.
3. Force-stop этих аппов (или ребут).
4. Проверь лог: `logcat | grep GSUnlock` — должно быть `hooked ... -> true`.
