package xyz.a202132.app.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeTypeConverter
import xyz.a202132.app.data.model.SubscriptionGroup
import xyz.a202132.app.data.model.SubscriptionLink
import xyz.a202132.app.util.DatabasePassphraseManager
import java.io.File
import java.io.RandomAccessFile

@Database(
    entities = [Node::class, SubscriptionGroup::class, SubscriptionLink::class],
    version = 6,
    exportSchema = false
)
@TypeConverters(NodeTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    abstract fun subscriptionDao(): SubscriptionDao
    
    companion object {
        private const val TAG = "AppDatabase"
        private const val DB_NAME = "firefly_vpn.db"
        private val SQLITE_PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        @Volatile
        private var sqlCipherLibLoaded = false

        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                ensureSqlCipherLoaded()
                deleteLegacyPlaintextDatabaseIfNeeded(appContext)
                var instance = buildEncryptedDatabase(appContext)
                runCatching {
                    // Force open once to validate passphrase/file format, but do not close it here.
                    instance.openHelper.writableDatabase
                }.onFailure { error ->
                    Log.w(TAG, "Failed to open encrypted database, recreating.", error)
                    runCatching { instance.close() }
                    deleteDatabaseFiles(appContext)
                    instance = buildEncryptedDatabase(appContext)
                    runCatching { instance.openHelper.writableDatabase }
                        .getOrElse { throw it }
                }
                INSTANCE = instance
                instance
            }
        }

        private fun buildEncryptedDatabase(context: Context): AppDatabase {
            val passphrase = DatabasePassphraseManager.getOrCreatePassphrase(context)
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nodes ADD COLUMN subscriptionGroupId TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subscription_groups (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        autoUpdateEnabled INTEGER NOT NULL,
                        updateIntervalMinutes INTEGER NOT NULL,
                        deduplicateEnabled INTEGER NOT NULL,
                        lastUpdatedAt INTEGER NOT NULL,
                        lastError TEXT,
                        lastNodeCount INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subscription_links (
                        id TEXT NOT NULL PRIMARY KEY,
                        groupId TEXT NOT NULL,
                        url TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_links_groupId ON subscription_links(groupId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_subscription_links_groupId_url ON subscription_links(groupId, url)")
                db.execSQL("UPDATE nodes SET subscriptionGroupId = 'subscription_default' WHERE source = 'SUBSCRIPTION'")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscription_groups ADD COLUMN userAgent TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE nodes ADD COLUMN downloadTestStatus TEXT NOT NULL DEFAULT 'NOT_TESTED'"
                )
                db.execSQL(
                    "ALTER TABLE nodes ADD COLUMN uploadTestStatus TEXT NOT NULL DEFAULT 'NOT_TESTED'"
                )
                db.execSQL("ALTER TABLE nodes ADD COLUMN downloadTestMessage TEXT")
                db.execSQL("ALTER TABLE nodes ADD COLUMN uploadTestMessage TEXT")
                db.execSQL(
                    "UPDATE nodes SET downloadTestStatus = 'SUCCESS' WHERE downloadMbps > 0"
                )
                db.execSQL(
                    "UPDATE nodes SET uploadTestStatus = 'SUCCESS' WHERE uploadMbps > 0"
                )
            }
        }

        private fun ensureSqlCipherLoaded() {
            if (sqlCipherLibLoaded) return
            synchronized(this) {
                if (sqlCipherLibLoaded) return
                System.loadLibrary("sqlcipher")
                sqlCipherLibLoaded = true
            }
        }

        private fun deleteLegacyPlaintextDatabaseIfNeeded(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return

            val header = readHeader(dbFile, SQLITE_PLAIN_HEADER.size) ?: return
            if (!header.contentEquals(SQLITE_PLAIN_HEADER)) {
                return
            }

            Log.w(TAG, "Detected legacy plaintext database. Deleting and recreating encrypted database.")
            deleteDatabaseFiles(context)
        }

        private fun readHeader(file: File, length: Int): ByteArray? {
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val buf = ByteArray(length)
                    if (raf.read(buf) == length) buf else null
                }
            }.getOrNull()
        }

        private fun deleteIfExists(file: File) {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete database sidecar: ${file.absolutePath}")
            }
        }

        private fun deleteDatabaseFiles(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            context.deleteDatabase(DB_NAME)
            val parent = dbFile.parentFile ?: return
            deleteIfExists(File(parent, "$DB_NAME-wal"))
            deleteIfExists(File(parent, "$DB_NAME-shm"))
            deleteIfExists(File(parent, "$DB_NAME-journal"))
        }
    }
}
