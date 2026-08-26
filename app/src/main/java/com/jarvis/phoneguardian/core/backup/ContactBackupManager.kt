package com.jarvis.phoneguardian.core.backup

import android.content.Context
import android.provider.ContactsContract
import android.net.Uri
import java.io.OutputStreamWriter

class ContactBackupManager(private val context: Context) {
    fun exportVcf(destination: Uri): Int {
        var count = 0
        val resolver = context.contentResolver
        resolver.openOutputStream(destination, "wt")?.use { raw ->
            OutputStreamWriter(raw, Charsets.UTF_8).use { writer ->
                resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                    null, null, "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE NOCASE"
                )?.use { contacts ->
                    val idIndex = contacts.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    val nameIndex = contacts.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    while (contacts.moveToNext()) {
                        val id = contacts.getString(idIndex)
                        val name = contacts.getString(nameIndex).orEmpty()
                        writer.appendLine("BEGIN:VCARD")
                        writer.appendLine("VERSION:3.0")
                        writer.appendLine("FN:${escape(name)}")
                        resolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
                            arrayOf(id), null
                        )?.use { phones ->
                            val numberIndex = phones.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            while (phones.moveToNext()) writer.appendLine("TEL:${escape(phones.getString(numberIndex).orEmpty())}")
                        }
                        writer.appendLine("END:VCARD")
                        count++
                    }
                }
            }
        } ?: error("Android could not open the contact backup destination.")
        return count
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
}
