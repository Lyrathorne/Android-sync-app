# DeviceSync Keyboard manual test matrix

Эмулятор и камера для текущей проверки не используются. Финальный ручной тест выполняется на физическом Android-устройстве после установки APK.

| Сценарий | Ожидаемый результат |
|---|---|
| Включение IME в Android Settings | `DeviceSync Keyboard` видна, Android показывает стандартное предупреждение |
| Выбор через системный picker | Клавиатура появляется и остаётся доступной после закрытия DeviceSync Activity |
| Обычное поле RU/EN | Ввод, RU/EN, ShiftOnce и двойной Shift/Caps Lock работают |
| Пустое текстовое поле | Smart Strip показывает только настройки, clipboard и emoji; отдельной строки T9 нет |
| Начат набор слова | Та же строка без изменения высоты показывает три подсказки вместо инструментов |
| Пробел после слова | Smart Strip возвращает инструменты без скачка высоты клавиатуры |
| Быстрый ввод 100–200 символов | Нет пропусков, перестановки символов, растущей задержки и мигания Smart Strip |
| Набор двумя большими пальцами | Мультитач не оставляет клавиши в pressed-состоянии |
| ACTION_CANCEL / уход пальца | Подсветка клавиши снимается сразу, символ не дублируется |
| Удержание Backspace | Повтор начинается предсказуемо и прекращается сразу после отпускания |
| Вибрация SYSTEM/CUSTOM/OFF | SYSTEM следует Android, CUSTOM ощущается на ACTION_DOWN, OFF полностью молчит |
| Слабая/средняя/сильная вибрация | Три коротких импульса различимы, но не имеют длинного хвоста |
| Цифровой ряд выключен | Клавиатура занимает около 232 dp без системного navigation inset |
| Цифровой ряд включён | Клавиатура занимает около 278 dp без системного navigation inset |
| Email / URL | Буквенная раскладка работает; специальные символы доступны через `?123` |
| Число / decimal / телефон | Открывается цифровая раскладка, доступны `+`, `.`, `,` и Backspace |
| Emoji | Unicode emoji вставляется без повреждения surrogate pair |
| Unicode Backspace | Один Backspace удаляет один code point, включая emoji |
| Done/Next/Go/Search/Send | Enter вызывает соответствующий `performEditorAction` |
| Password/PIN | Нет suggestions и clipboard; показана отметка защищённого поля |
| No personalized learning | Включается тот же sensitive policy |
| Clipboard Android → Windows | При включённой auto-sync текст появляется на Windows без echo loop |
| Clipboard Android → Windows offline | Последнее значение сохраняется и уходит после reconnect |
| Clipboard Windows → Android | Значение появляется в Android clipboard и истории с источником Windows |
| Clipboard history | Дедупликация, вставка, закрепление, удаление и очистка работают |
| Перезапуск процесса | Зашифрованная clipboard history расшифровывается тем же Keystore key |
| Нет Wi-Fi при первой загрузке модели | Показана понятная ошибка, UI не зависает |
| Большой системный шрифт | Клавиши остаются нажимаемыми; labels не перекрывают соседние rows |
| Landscape | Клавиатура помещается и не закрывает toolbar actions |
| Activity уничтожена | Default IME продолжает работать; сеть принадлежит foreground connection service |
| File transfer regression | Передача Android → Windows по-прежнему завершается успешно |
| Text share regression | Ручная отправка текста по-прежнему работает |

Известные ручные проверки, обязательные перед production release: TalkBack, OEM battery killers, multi-window, hardware keyboard, Android 8/10/13/16, low-memory process recreation и latency benchmark на слабом устройстве.
