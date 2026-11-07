package ru.stenokot.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import ru.stenokot.app.ControllerOverride
import ru.stenokot.app.ControllerProvider
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.nav.Route

/** Отладочная сборка показывает демо-данные, пока запуск не попросит рабочий конвейер. */
private class DemoOverride : ControllerOverride {
    private val fake = FakeController()
    private var real = false

    override fun controller(context: Context, intent: Intent?): StenoController? {
        real = intent?.getBooleanExtra(EXTRA_REAL, false) == true
        return if (real) null else fake
    }

    override fun routeFor(intent: Intent?): Route? {
        if (real) return null
        val id = intent?.getStringExtra(EXTRA_DEMO) ?: return null
        return fake.show(id)
    }

    private companion object {
        const val EXTRA_DEMO = "demo"
        const val EXTRA_REAL = "real"
    }
}

/**
 * Ставит подмену контроллера раньше создания активности. Провайдер объявлен только в манифесте
 * отладочной сборки, поэтому демо-данные не попадают в релиз.
 */
class DebugBootstrap : ContentProvider() {
    override fun onCreate(): Boolean {
        ControllerProvider.override = DemoOverride()
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
