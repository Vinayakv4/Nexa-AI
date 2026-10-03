package com.nexa.ai.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "messages",
    indices = [androidx.room.Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val attachmentName: String? = null,
    val attachmentText: String? = null,
    val timestamp: Long
)

@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val prompt: String,
    val scheduleType: String, // "once" | "daily"
    val scheduleHour: Int = -1, // -1 = run now / not scheduled
    val scheduleMinute: Int = -1,
    val status: String = "queued", // queued | running | done | failed | cancelled
    val result: String = "",
    val snapshotId: String? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET model = :model, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateModel(id: Long, model: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countForConversation(conversationId: Long): Int

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)
}

@Dao
interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE scheduleType = 'daily' OR status = 'queued' ORDER BY createdAt DESC")
    fun observeScheduled(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun getById(id: Long): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE status = 'queued' ORDER BY createdAt ASC")
    suspend fun queued(): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE scheduleType = 'daily' AND status IN ('queued','done','failed')")
    suspend fun dailyTasks(): List<AgentTaskEntity>

    @Insert
    suspend fun insert(task: AgentTaskEntity): Long

    @Query("UPDATE agent_tasks SET status = :status, result = :result, startedAt = :startedAt, finishedAt = :finishedAt, snapshotId = :snapshotId WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, result: String, startedAt: Long?, finishedAt: Long?, snapshotId: String? = null)

    @Query("UPDATE agent_tasks SET status = :status, result = :result, finishedAt = :finishedAt WHERE id = :id")
    suspend fun finish(id: Long, status: String, result: String, finishedAt: Long)

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM agent_tasks WHERE scheduleType = 'chat' AND status IN ('done','failed','cancelled')")
    suspend fun purgeChatRuns()

    @Query("DELETE FROM agent_tasks WHERE status IN ('done','failed','cancelled') AND scheduleType != 'daily'")
    suspend fun clearFinished()
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, AgentTaskEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun agentTaskDao(): AgentTaskDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS agent_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        scheduleType TEXT NOT NULL,
                        scheduleHour INTEGER NOT NULL,
                        scheduleMinute INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        result TEXT NOT NULL,
                        snapshotId TEXT,
                        createdAt INTEGER NOT NULL,
                        startedAt INTEGER,
                        finishedAt INTEGER
                    )"""
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vchat.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
