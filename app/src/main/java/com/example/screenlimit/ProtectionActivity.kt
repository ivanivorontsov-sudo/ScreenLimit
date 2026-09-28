package com.example.screenlimit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

/** «Максимальная защита»: Device Owner + Lock Task. Инструкция и управление. */
class ProtectionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); title = "Максимальная защита" }
    override fun onResume() { super.onResume(); if (Store.get(this).state.locked) finish() else render() }

    private fun render() {
        val owner = Owner.isDeviceOwner(this)
        val store = Store.get(this)
        scrollPage {
            card {
                text(if (owner) "🛡 Включено: «Стоп-экран» — владелец устройства" else "Сейчас: базовый режим", 17f, bold = true).id = R.id.ownerStatus
                text(if (Owner.isAdmin(this@ProtectionActivity)) "Администратор устройства: включён" else "Администратор устройства: выключен", 14f)
            }
            section("ЧТО ДАЁТ")
            text("• Во время блокировки включается режим киоска (Lock Task): кнопки «Домой» и «Недавние» не выводят из экрана блокировки, доступны только звонки, SMS и разрешённые приложения.\n" +
                "• Запрет удаления «Стоп-экран», остановки и очистки его данных.\n• Запрет безопасного режима, сброса к заводским настройкам, добавления пользователей.\n" +
                "• Во время блокировки — запрет смены даты/времени, установки и удаления приложений.", 14f)

            if (!owner) {
                section("КАК ВКЛЮЧИТЬ (нужен компьютер)")
                text("Важно: Android разрешает назначить владельца только если на телефоне НЕТ добавленных аккаунтов (Google, Samsung, Xiaomi и т.п.). Обычно это значит: временно удалить аккаунты (Настройки → Аккаунты), выполнить команду, затем добавить аккаунты обратно. Второй вариант — сделать это сразу после сброса, до входа в аккаунт.", 14f, color = getColor(R.color.danger))
                text("1. На телефоне: Настройки → О телефоне → 7 раз нажмите «Номер сборки».\n" +
                    "2. Настройки → Система → Для разработчиков → включите «Отладка по USB».\n" +
                    "3. На компьютере установите Android Platform Tools (adb): developer.android.com/tools/releases/platform-tools\n" +
                    "4. Подключите телефон кабелем, разрешите отладку на экране телефона.\n" +
                    "5. На компьютере выполните:", 14f, topDp = 8)
                card { text(Owner.ADB_COMMAND, 14f, bold = true).setTextIsSelectable(true) }
                button("Скопировать команду", style = 1) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", Owner.ADB_COMMAND))
                    Toast.makeText(this@ProtectionActivity, "Скопировано", Toast.LENGTH_SHORT).show()
                }
                text("6. Должно появиться «Success: Device owner set…». Если ошибка «already some accounts on the device» — удалите аккаунты (см. выше). Если «already several users» — удалите гостя/второго пользователя.\n" +
                    "7. Вернитесь в приложение — статус сменится на «владелец устройства». Отладку по USB после этого можно выключить.", 14f)
            } else {
                section("НАСТРОЙКИ")
                row {
                    text("Применять политики владельца", 15f).weight()
                    addView(MaterialSwitch(context).apply { isChecked = store.ownerProtection; isEnabled = false })
                }
                row {
                    text("Запрещать отладку по USB во время блокировки (закрывает обход через adb)", 14f).weight()
                    addView(MaterialSwitch(context).apply {
                        isChecked = store.blockAdbDuringLock
                        setOnCheckedChangeListener { _, v -> if (v) { store.blockAdbDuringLock = true } else { isChecked = true; Toast.makeText(context, "Выключение — только через снятие защиты (10 подтверждений)", Toast.LENGTH_LONG).show() } }
                    })
                }
                section("КАК СНЯТЬ")
                text("Снять режим владельца можно только отсюда и только после 10 подтверждений. После этого приложение можно будет удалить обычным способом (сначала снимите и права администратора кнопкой ниже).", 14f)
                button("Снять максимальную защиту (10 подтверждений)", style = 1) {
                    startActivity(Intent(this@ProtectionActivity, ExitActivity::class.java).putExtra(ExitActivity.EXTRA_PURPOSE, ExitActivity.P_OWNER))
                }.id = R.id.btnRemoveOwner
            }
            section("УДАЛЕНИЕ ПРИЛОЖЕНИЯ")
            text("Пока включены права администратора, «Стоп-экран» нельзя удалить. Снять их — только после 10 подтверждений.", 14f)
            button("Снять права администратора (10 подтверждений)", style = 2) {
                startActivity(Intent(this@ProtectionActivity, ExitActivity::class.java).putExtra(ExitActivity.EXTRA_PURPOSE, ExitActivity.P_ADMIN))
            }.isEnabled = Owner.isAdmin(this@ProtectionActivity)
        }
    }
}
