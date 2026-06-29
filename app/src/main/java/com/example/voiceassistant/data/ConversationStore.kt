package com.example.voiceassistant.data

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local-first persistence of conversations (Phase 1 = text turns only).
 *
 * Per `architecture.md` §5: the verbatim thread is stored on-device and never leaves it. Each
 * conversation owns an ordered list of turns. A turn may reference one media attachment (image or
 * audio) by app-private file path — never the bytes. The running summary and windowed prompt
 * assembly are deferred to M5.
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
    val mediaPath: String? = null,   // app-private file path of an attachment, if any
    val mediaKind: String? = null,   // "IMAGE" | "AUDIO"
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A user-defined "instruction" skill: free-form markdown the user writes to teach the assistant new
 * behaviours (a playbook/persona). Enabled skills are injected into the model's system context — they
 * steer responses and how the built-in tools are used, but make no external calls. Built-in tools are
 * compiled Kotlin in `tools/AssistantTools.kt`. Fully CRUD-able from the Skills screen. See
 * `architecture.md` §5b.
 */
@Entity(tableName = "instruction_skills")
data class InstructionSkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,            // short label shown in the list
    val description: String = "", // one-line summary kept in the prompt; full body loaded on demand
    val instructions: String,    // markdown body loaded via the get_skill tool when invoked
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface InstructionSkillDao {
    @Query("SELECT * FROM instruction_skills ORDER BY updatedAt DESC")
    suspend fun getAll(): List<InstructionSkillEntity>

    @Insert
    suspend fun insert(skill: InstructionSkillEntity): Long

    @Update
    suspend fun update(skill: InstructionSkillEntity)

    @Query("DELETE FROM instruction_skills WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * A remote MCP server the user configured. On Android we connect over Streamable HTTP, so a server is
 * a URL plus optional auth headers; when enabled, its tools are registered with the model. See §5b.
 */
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val headersJson: String?,   // JSON object of request headers (e.g. Authorization), or null
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY updatedAt DESC")
    suspend fun getAll(): List<McpServerEntity>

    @Insert
    suspend fun insert(server: McpServerEntity): Long

    @Update
    suspend fun update(server: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: Long)
}

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

@Database(
    entities = [ConversationEntity::class, TurnEntity::class, InstructionSkillEntity::class, McpServerEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun turnDao(): TurnDao
    abstract fun instructionSkillDao(): InstructionSkillDao
    abstract fun mcpServerDao(): McpServerDao
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

/** v2 → v3: turns can reference a single image/audio attachment by app-private file path. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE turns ADD COLUMN mediaPath TEXT")
        db.execSQL("ALTER TABLE turns ADD COLUMN mediaKind TEXT")
    }
}

/** v3 → v4: original user-defined Web API skills table (superseded by instruction_skills in v5). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS custom_skills " +
                "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                "description TEXT NOT NULL, httpMethod TEXT NOT NULL, urlTemplate TEXT NOT NULL, " +
                "paramsJson TEXT NOT NULL, headersJson TEXT, enabled INTEGER NOT NULL DEFAULT 1, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
        )
    }
}

/** v4 → v5: custom skills become free-form markdown "instruction" skills (see `architecture.md` §5b). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS instruction_skills " +
                "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                "instructions TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
        )
        db.execSQL("DROP TABLE IF EXISTS custom_skills")
    }
}

/** v5 → v6: remote MCP servers (agent tool-calling over Streamable HTTP, see `architecture.md` §5b). */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mcp_servers " +
                "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, " +
                "headersJson TEXT, enabled INTEGER NOT NULL DEFAULT 1, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
        )
    }
}

/** v6 → v7: instruction skills get a short `description` (kept in the prompt; full body loaded on demand). */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE instruction_skills ADD COLUMN description TEXT NOT NULL DEFAULT ''")
    }
}

class ConversationStore(context: Context) {
    private val appContext = context.applicationContext
    private val db = Room.databaseBuilder(
        appContext,
        ConversationDatabase::class.java,
        "conversation.db"
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
        .fallbackToDestructiveMigration()
        .build()

    private val conversationDao = db.conversationDao()
    private val turnDao = db.turnDao()
    private val instructionSkillDao = db.instructionSkillDao()
    private val mcpServerDao = db.mcpServerDao()

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

    suspend fun append(
        conversationId: Long,
        role: String,
        text: String,
        mediaPath: String? = null,
        mediaKind: String? = null,
    ): Long {
        val rowId = turnDao.insert(
            TurnEntity(
                conversationId = conversationId,
                role = role,
                text = text,
                mediaPath = mediaPath,
                mediaKind = mediaKind,
            )
        )
        conversationDao.touch(conversationId, System.currentTimeMillis())
        return rowId
    }

    /**
     * Copy a picked `content://` [uri] into app-private storage and return the absolute file path.
     * The runtime reads media from a real file path (`Content.ImageFile` / `Content.AudioFile`), and
     * a picked Uri's read grant is transient — so we copy now and reference the durable copy forever.
     * Must be called off the main thread.
     */
    // --- Custom (markdown instruction) skills ---

    suspend fun loadInstructionSkills(): List<InstructionSkillEntity> = instructionSkillDao.getAll()

    /** Insert (id == 0) or update an existing instruction skill; returns the row id. */
    suspend fun upsertInstructionSkill(skill: InstructionSkillEntity): Long {
        return if (skill.id == 0L) {
            instructionSkillDao.insert(skill.copy(createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
        } else {
            instructionSkillDao.update(skill.copy(updatedAt = System.currentTimeMillis()))
            skill.id
        }
    }

    suspend fun deleteInstructionSkill(id: Long) = instructionSkillDao.delete(id)

    // --- MCP servers ---

    suspend fun loadMcpServers(): List<McpServerEntity> = mcpServerDao.getAll()

    suspend fun upsertMcpServer(server: McpServerEntity): Long {
        return if (server.id == 0L) {
            mcpServerDao.insert(server.copy(createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
        } else {
            mcpServerDao.update(server.copy(updatedAt = System.currentTimeMillis()))
            server.id
        }
    }

    suspend fun deleteMcpServer(id: Long) = mcpServerDao.delete(id)

    fun saveMedia(uri: Uri, extension: String): String {
        val mediaDir = java.io.File(appContext.filesDir, "media").apply { mkdirs() }
        val ext = extension.ifBlank { "bin" }
        val outFile = java.io.File(mediaDir, "${java.util.UUID.randomUUID()}.$ext")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(outFile).use { output -> input.copyTo(output) }
        } ?: throw java.io.IOException("Unable to open media stream for $uri")
        return outFile.absolutePath
    }

    fun close() = db.close()
}
