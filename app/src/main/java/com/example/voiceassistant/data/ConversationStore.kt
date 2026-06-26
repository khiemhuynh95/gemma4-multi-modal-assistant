package com.example.voiceassistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local-first persistence of conversations (Phase 1 = text turns only).
 *
 * Per `architecture.md` §5: the verbatim thread is stored on-device and never leaves it. Each
 * conversation owns an ordered list of turns. Captured images, the running summary, and windowed
 * prompt assembly are deferred to M5.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "turns", indices = [Index("conversationId")])
data class TurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,          // "user" | "assistant"
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ConversationEntity>

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE conversations SET updatedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TurnDao {
    @Query("SELECT * FROM turns WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun getForConversation(conversationId: Long): List<TurnEntity>

    @Insert
    suspend fun insert(turn: TurnEntity): Long

    @Query("DELETE FROM turns WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)
}

@Database(entities = [ConversationEntity::class, TurnEntity::class], version = 2, exportSchema = false)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun turnDao(): TurnDao
}

/** v1 (single flat thread) → v2 (multi-conversation): keep existing turns under one chat. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS conversations " +
                "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
        )
        db.execSQL("ALTER TABLE turns ADD COLUMN conversationId INTEGER NOT NULL DEFAULT 1")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_turns_conversationId ON turns (conversationId)")
        val now = System.currentTimeMillis()
        // Pre-existing turns all default to conversationId = 1; create that chat to own them.
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAt, updatedAt) VALUES (1, 'Conversation', $now, $now)"
        )
    }
}

class ConversationStore(context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        ConversationDatabase::class.java,
        "conversation.db"
    )
        .addMigrations(MIGRATION_1_2)
        .fallbackToDestructiveMigration()
        .build()

    private val conversationDao = db.conversationDao()
    private val turnDao = db.turnDao()

    suspend fun loadConversations(): List<ConversationEntity> = conversationDao.getAll()

    suspend fun createConversation(title: String): Long =
        conversationDao.insert(ConversationEntity(title = title))

    suspend fun renameConversation(id: Long, title: String) =
        conversationDao.updateTitle(id, title)

    suspend fun deleteConversation(id: Long) {
        turnDao.deleteForConversation(id)
        conversationDao.delete(id)
    }

    suspend fun clearTurns(conversationId: Long) =
        turnDao.deleteForConversation(conversationId)

    suspend fun loadTurns(conversationId: Long): List<TurnEntity> =
        turnDao.getForConversation(conversationId)

    suspend fun append(conversationId: Long, role: String, text: String): Long {
        val rowId = turnDao.insert(TurnEntity(conversationId = conversationId, role = role, text = text))
        conversationDao.touch(conversationId, System.currentTimeMillis())
        return rowId
    }

    fun close() = db.close()
}
