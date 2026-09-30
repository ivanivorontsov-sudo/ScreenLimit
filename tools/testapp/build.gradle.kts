// Тестовое «разрешённое приложение» для проверки исключений на эмуляторе (не входит в «Стоп-экран»).
plugins { id("com.android.application") version "8.2.2" }
android {
    namespace = "com.example.sltest"
    compileSdk = 34
    defaultConfig { applicationId = "com.example.sltest"; minSdk = 26; targetSdk = 30; versionCode = 1; versionName = "1" }
}
