# Исследование: как сделать «родительский контроль для себя» на Android без root (2025–2026)

Цель: после исчерпания дневного лимита телефон блокируется; доступны только звонки, SMS и экстренный вызов;
выйти можно, только 10 раз подтвердив намерение. Нужен способ, который сложнее всего обойти на обычном Android.

## 1. Как узнать, сколько используется телефон и что сейчас на экране

| Механизм | Что даёт | Минусы |
|---|---|---|
| **UsageStatsManager** (`PACKAGE_USAGE_STATS`, спец. доступ «История использования») | Итоги по приложениям за день, события `MOVE_TO_FOREGROUND/BACKGROUND`, `SCREEN_INTERACTIVE`. Официальный источник для экранного времени. | Задержка ~0,7–1 с при опросе; сам по себе **ничего не блокирует**. |
| **AccessibilityService** (`TYPE_WINDOW_STATE_CHANGED`) | Мгновенно (<100 мс) узнаёт приложение/окно на переднем плане, может читать заголовки окон (напр. страница Настроек), выполнять «Назад/Домой», рисовать `TYPE_ACCESSIBILITY_OVERLAY`. Так работают AppBlock, Stay Focused, Lock Me Out, Opal. | Чувствительное разрешение; на Android 13+ для APK не из магазина — «ограниченные настройки»; в Google Play — декларация и заметное раскрытие. |

Итог: **UsageStats — для учёта и статистики, Accessibility — для мгновенного принуждения**, оба источника дублируют друг друга
(если спец. возможности отключены, сервис по-прежнему ловит запрещённое приложение опросом UsageStats и возвращает экран блокировки).

Источники: [UsageStatsManager — Android Developers](https://developer.android.com/reference/android/app/usage/UsageStatsManager);
[«Why I removed AccessibilityService…» (DEV, сравнение задержек)](https://dev.to/lui61140/why-i-removed-accessibilityservice-from-my-android-app-and-finally-got-into-play-store-4i52);
[Flint — Android technical strategy (Accessibility как основной путь, UsageStats — резерв)](https://github.com/Prakashmaheshwaran/flint-app/blob/main/docs/research/03-android-technical-strategy.md);
[Stay Focused (Play)](https://play.google.com/store/apps/details?id=com.stayfocused); [AppBlock — Strict Mode / Allowlist mode](https://appblock.app/).

## 2. Как блокировать

| Способ | Как работает | Надёжность |
|---|---|---|
| **Accessibility + свой полноэкранный экран** | При смене окна на запрещённое — сразу `TYPE_ACCESSIBILITY_OVERLAY` + запуск своей Activity. Так делают AppBlock/Stay Focused/Lock Me Out. | Хорошая. Обходы: отключить спец. возможности, остановить/удалить приложение, безопасный режим. |
| **SYSTEM_ALERT_WINDOW (overlay)** | Окно поверх всех приложений. | Нужен для запуска Activity из фона (Android 10+ ограничивает фоновый запуск), сам по себе легко обходится (шторка, «Недавние»). Используем как вспомогательное. |
| **Device Admin** | `lockNow()`, запрет удаления, пока админ активен. | Удаление требует сначала снять админа (страница «Администраторы устройства» — закрываем через Accessibility). Большинство политик для обычного админа устарели с Android 10. |
| **Device Owner + Lock Task (киоск)** | `adb shell dpm set-device-owner …`; `setLockTaskPackages()` (своё приложение + звонилка + SMS + телеком), `setLockTaskFeatures()` (шторка/клавиатура блокировки/кнопка питания), `startLockTask()`. «Домой»/«Недавние» не работают, запуск чужих Activity запрещён системой. Плюс `setUninstallBlocked`, `setUserControlDisabledPackages` (нельзя «Остановить»/«Очистить данные»), `DISALLOW_SAFE_BOOT`, `DISALLOW_FACTORY_RESET`, `DISALLOW_ADD_USER`, `DISALLOW_CONFIG_DATE_TIME`, `DISALLOW_APPS_CONTROL`, `DISALLOW_(UN)INSTALL_APPS`, опционально `DISALLOW_DEBUGGING_FEATURES`. | **Максимальная без root.** Минусы: назначить владельца можно только на устройстве без аккаунтов (или сразу после сброса) и через компьютер. |
| **setPackagesSuspended** (так делает Family Link / Digital Wellbeing) | Система «приостанавливает» приложения: иконка серая, не запускаются. | Доступно только владельцу/профилю или системным приложениям. Family Link — это отдельный DPC; Digital Wellbeing — системное приложение. |

Для звонков в режиме киоска нужно разрешить не только звонилку, но и `com.android.server.telecom`, пакет in-call UI и т. п. —
у Samsung, например, `com.samsung.android.incallui`, `com.samsung.android.dialer`, `com.android.incallui.call.dialog`.

Источники: [Lock task mode — Android Enterprise](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode);
[Dedicated devices cookbook (DISALLOW_SAFE_BOOT, DISALLOW_FACTORY_RESET…)](https://developer.android.com/work/dpc/dedicated-devices/cookbook);
[Samsung Knox: Unable to place calls in kiosk mode (список пакетов)](https://docs.samsungknox.com/admin/knox-platform-for-enterprise/knox-service-plugin/kbas/kba-877-unable-to-place-calls-in-kiosk-mode/);
[Relution: звонки в киоске](https://hub.relution.io/en/docs/android-enterprise/knowledge-base/call-ui/);
[Hexnode: звонки и SMS в киоске](https://www.hexnode.com/forums/topic/allow-phone-calls-and-sms-in-android-multi-app-kiosk-mode/);
[DevicePolicyManager.setLockTaskPackages](https://learn.microsoft.com/en-us/dotnet/api/android.app.admin.devicepolicymanager.setlocktaskpackages);
[Destination — open-source блокировщик на Device Owner](https://github.com/creativeidiot123/destination);
[Family Link: время использования](https://support.google.com/families/answer/7103340); [Android Enterprise Security (suspend)](https://developer.android.google.cn/work/dpc/security).

## 3. Известные обходы и контрмеры

| Обход | Базовый режим | Режим владельца |
|---|---|---|
| **Безопасный режим** (все сторонние приложения выключены) | Нельзя запретить. После выхода из безопасного режима блокировка восстанавливается (состояние хранится на диске). AppBlock прямо пишет, что не может это предотвратить. | `DISALLOW_SAFE_BOOT` — запрещено. |
| **Остановить / Очистить данные** в «О приложении» | Во время блокировки (и всегда в «Строгом режиме») страница «О приложении» «Стоп-экран» закрывается Accessibility. Сервис — `START_STICKY`, «пульс» AlarmManager каждые 15 мин. | `setUserControlDisabledPackages` — кнопок нет; `DISALLOW_APPS_CONTROL` во время блокировки. |
| **Удаление** | Нужен активный Device Admin → сначала снять админа; страницы «Администраторы» и установщик пакетов закрываются во время блокировки. | `setUninstallBlocked` + `DISALLOW_UNINSTALL_APPS` (проверено: `DELETE_FAILED_USER_RESTRICTED`). |
| **Отключить спец. возможности** | Страница спец. возможностей закрывается во время блокировки; если сервис всё же отключён — страховка опросом UsageStats + onUserLeaveHint возвращают экран блокировки (с задержкой ~1–5 с). | Lock Task не зависит от Accessibility. |
| **Перевод часов / часового пояса** | Учёт времени только по `SystemClock.elapsedRealtime()` (монотонные). «Доверенное» время = якорь + прошедшее elapsed; скачки настенных часов >2 мин игнорируются (записываются в историю как «tamper»). После перезагрузки время не может «уйти» раньше последнего доверенного момента. Смена пояса не меняет момент времени → ни сброса, ни «подарка». | Плюс `DISALLOW_CONFIG_DATE_TIME` во время блокировки. |
| **Перезагрузка** | `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` → сервис и экран блокировки; служба спец. возможностей поднимается системой ещё раньше и сразу показывает экран. | Lock Task восстанавливается при первом показе экрана. |
| **Сброс к заводским** | Не запретить (но это уничтожает все данные — «цена» обхода огромна). | `DISALLOW_FACTORY_RESET` (из настроек; сброс из recovery кнопками остаётся возможен). |
| **adb** (`pm uninstall`, `am force-stop`, `dpm remove-active-admin`) | Режим разработчика закрыт во время блокировки; USB-отладку лучше выключить. | Опция «запрещать отладку по USB во время блокировки» (`DISALLOW_DEBUGGING_FEATURES`). `dpm remove-active-admin` для владельца не работает в release-сборке (только testOnly). |
| **Второй пользователь / гость** | — | `DISALLOW_ADD_USER`. |
| **Шторка уведомлений / быстрые настройки** | Нажатие на уведомление чужого приложения открывает его → сразу возврат на экран блокировки. | В киоске действия уведомлений не работают. |
| **Выдача «ослабленных» настроек** | Во время блокировки менять нельзя; вне блокировки ослабление (лимит больше, выключение, расширение белого списка, сдвиг окна, выключение строгого режима) — только после 10 подтверждений. Импорт JSON подчиняется тем же правилам. | То же. |

Источники: [AppBlock — Safe mode](https://appblock.app/appblock-does-not-work-after-entering-safe-mode/);
[Cursed Screen — «Can you make an unbreakable blocker?»](https://cursedscreen.com/blog/unbreakable-app-blocker/);
[Blok — 7 методов блокировки](https://www.blok.so/resources/how-to-block-apps-on-android-7-methods-ranked-by-how-well-they-actually-work).

## 4. Android 13/14/15: что учесть

* **Ограниченные настройки (Android 13+)**: APK, установленный не через магазин (не session-based install), не может включить спец. возможности,
  пока пользователь не нажмёт *Настройки → Приложения → «Стоп-экран» → ⋮ → «Разрешить ограниченные настройки»*.
  Пункт ⋮ появляется **только после** первой неудачной попытки включить службу.
  [Google: Learn about restricted settings](https://support.google.com/android/answer/12623953) ·
  [Kaspersky: Restricted Settings в 13/14](https://www.kaspersky.com/blog/android-restricted-settings/49991/) ·
  [CleanBrowsing: пошагово по производителям](https://cleanbrowsing.org/support/mobile/disable-restricted-settings-android)
* **POST_NOTIFICATIONS** (13+) — runtime-разрешение.
* **USE_FULL_SCREEN_INTENT** (14+) — по умолчанию только для звонилок/будильников; проверяем `NotificationManager.canUseFullScreenIntent()`,
  иначе — `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. [AOSP: FSI limits](https://source.android.com/docs/core/permissions/fsi-limits)
* **SCHEDULE_EXACT_ALARM** (14+) — для новых установок по умолчанию запрещён; `canScheduleExactAlarms()` →
  `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`. `USE_EXACT_ALARM` не используем: он разрешён в Play только будильникам/календарям.
  [Behavior changes: Android 14](https://developer.android.com/about/versions/14/behavior-changes-14)
* **Типы foreground service** (14+): выбран `specialUse` с `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. `dataSync` не подходит (6 ч/сутки лимит
  в 15+ и запрет старта из BOOT_COMPLETED). [Behavior changes: Android 15](https://developer.android.com/about/versions/15/behavior-changes-15)

## 5. Политика Google Play по Accessibility

Приложение **не** является инструментом для людей с ОВЗ → `isAccessibilityTool="false"`; для публикации в Play понадобятся
декларация в Play Console, заметное раскрытие в приложении и согласие пользователя (у нас: описание службы + экран онбординга).
Помечать себя `isAccessibilityTool=true` нельзя — Play отклонит, Play Protect заблокирует.
[Play Console: Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491) ·
[Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/9888170) ·
[Android 16: accessibilityDataSensitive](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data)

## 6. «Убийцы» фоновых приложений у производителей

| Оболочка | Что включить |
|---|---|
| Xiaomi / Redmi / POCO (MIUI/HyperOS) | Автозапуск; Экономия батареи → «Нет ограничений»; закрепить в недавних; «Всплывающие окна в фоне», «Экран блокировки». |
| Huawei / Honor | Запуск приложений → вручную: автозапуск, косвенный запуск, работа в фоне; Батарея → «Не оптимизировать». |
| Samsung (One UI) | Ограничения фонового использования → убрать из «Спящих/Глубоко спящих», добавить в «Никогда не спят»; Батарея → «Без ограничений». |
| OPPO/realme/OnePlus/vivo | Фоновая активность / «Без ограничений», автозапуск, закрепить. |

Плюс стандартный запрос `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Советы показываются в онбординге по `Build.MANUFACTURER`.
[dontkillmyapp.com — Xiaomi](https://dontkillmyapp.com/xiaomi) · [Samsung](https://dontkillmyapp.com/samsung) ·
[Huawei: фоновая защита](https://consumer.huawei.com/en/support/content/en-us15850065/) · [Xiaomi: автозапуск](https://www.mi.com/global/support/faq/details/KA-507611/)

## 7. Выбранная архитектура

1. **Базовый режим** (обычные разрешения): UsageStats (учёт + статистика) + AccessibilityService (мгновенный возврат, закрытие
   опасных страниц Настроек, `TYPE_ACCESSIBILITY_OVERLAY`) + Device Admin (защита от удаления) + overlay + foreground service `specialUse`
   + точные будильники по `ELAPSED_REALTIME_WAKEUP` + исключение из оптимизации батареи.
2. **«Максимальная защита»** (Device Owner через adb): те же механизмы + Lock Task с белым списком (своё приложение, звонилка по умолчанию,
   SMS по умолчанию, телеком/in-call UI, контакты, экстренные службы, пользовательский белый список), запрет удаления/остановки/очистки,
   безопасного режима, сброса, пользователей, смены времени (во время блокировки), установки/удаления приложений (во время блокировки).
   Снятие владельца — только из приложения после 10 подтверждений (`clearDeviceOwnerApp`).
