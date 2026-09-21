package com.trevor.assistant

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "conversation_messages")
data class TrevorConversationMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val role: String,
    val content: String,
    val provider: String?,
    val timestamp: Long
)

@Entity(tableName = "project_memory")
data class TrevorProjectMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val content: String,
    val timestamp: Long
)

@Entity(tableName = "trevor_tasks")
data class TrevorTask(
    @PrimaryKey val id: String,
    val title: String,
    val action: String,
    val triggerAt: Long,
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "long_term_memory")
data class TrevorLongTermMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val timestamp: Long
)

@Dao
interface TrevorMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: TrevorConversationMessage)

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun messages(conversationId: String): List<TrevorConversationMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(memory: TrevorProjectMemory)

    @Query("SELECT * FROM project_memory WHERE projectId = :projectId ORDER BY timestamp DESC LIMIT 100")
    suspend fun project(projectId: String): List<TrevorProjectMemory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TrevorTask)

    @Query("SELECT * FROM trevor_tasks WHERE status = 'PENDING' ORDER BY triggerAt ASC")
    suspend fun pendingTasks(): List<TrevorTask>

    @Query("UPDATE trevor_tasks SET status = :status WHERE id = :id")
    suspend fun setTaskStatus(id: String, status: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: TrevorLongTermMemory)

    @Query("SELECT * FROM long_term_memory ORDER BY timestamp DESC LIMIT 100")
    suspend fun memories(): List<TrevorLongTermMemory>
}

@Database(
    entities = [TrevorConversationMessage::class, TrevorProjectMemory::class, TrevorTask::class, TrevorLongTermMemory::class],
    version = 2,
    exportSchema = false
)
abstract class TrevorDatabase : RoomDatabase() {
    abstract fun memoryDao(): TrevorMemoryDao

    companion object {
        private val TREVOR_MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS trevor_tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, action TEXT NOT NULL, triggerAt INTEGER NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }
        @Volatile private var instance: TrevorDatabase? = null
        fun get(context: Context): TrevorDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TrevorDatabase::class.java,
                "trevor_memory.db"
            ).addMigrations(TREVOR_MIGRATION_1_2).build().also { instance = it }
        }
    }
}

object TrevorPersistentMemory {
    suspend fun saveMessage(context: Context, conversationId: String, role: String, content: String, provider: String?) =
        withContext(Dispatchers.IO) {
            TrevorDatabase.get(context).memoryDao().insertMessage(
                TrevorConversationMessage(conversationId = conversationId, role = role, content = content, provider = provider, timestamp = System.currentTimeMillis())
            )
        }

    suspend fun recentConversation(context: Context, conversationId: String, limit: Int = 20): List<TrevorConversationMessage> =
        withContext(Dispatchers.IO) { TrevorDatabase.get(context).memoryDao().messages(conversationId).takeLast(limit) }

    suspend fun saveProjectMemory(context: Context, projectId: String, content: String) =
        withContext(Dispatchers.IO) {
            TrevorDatabase.get(context).memoryDao().insertProject(
                TrevorProjectMemory(projectId = projectId, content = content, timestamp = System.currentTimeMillis())
            )
        }

    suspend fun projectMemory(context: Context, projectId: String): List<TrevorProjectMemory> =
        withContext(Dispatchers.IO) { TrevorDatabase.get(context).memoryDao().project(projectId) }

    suspend fun saveTask(context: Context, task: TrevorTask) =
        withContext(Dispatchers.IO) { TrevorDatabase.get(context).memoryDao().insertTask(task) }

    suspend fun pendingTasks(context: Context): List<TrevorTask> =
        withContext(Dispatchers.IO) { TrevorDatabase.get(context).memoryDao().pendingTasks() }

    suspend fun setTaskStatus(context: Context, id: String, status: String) =
        withContext(Dispatchers.IO) { TrevorDatabase.get(context).memoryDao().setTaskStatus(id, status) }

    suspend fun saveLongTermMemory(context: Context, content: String) =
        withContext(Dispatchers.IO) {
            TrevorDatabase.get(context).memoryDao().insertMemory(
                TrevorLongTermMemory(content = content, timestamp = System.currentTimeMillis())
            )
        }

    suspend fun longTermMemory(context: Context): List<TrevorLongTermMemory> =
        withContext(Dispatchers.IO) { TrevorDatabase.get(context).memoryDao().memories() }
}
