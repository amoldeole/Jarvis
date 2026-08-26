package com.jarvis.phoneguardian

import android.app.Application
import com.jarvis.phoneguardian.core.database.AppDatabase

class PhoneGuardianApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
}
