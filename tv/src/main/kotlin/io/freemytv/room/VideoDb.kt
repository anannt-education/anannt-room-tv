package io.freemytv.room

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import io.anannt.roomtv.protocol.WhitelistItem
import io.anannt.roomtv.protocol.WhitelistStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One approved video or playlist. `type` is "video" or "playlist" ([io.anannt.roomtv.protocol.YtKind.wire]). */
@Entity(tableName = "whitelist", primaryKeys = ["type", "id"])
data class VideoEntity(
    val type: String,
    val id: String,
    val title: String,
    val addedAt: Long,
    val addedBy: String,
)

@Dao
interface VideoDao {
    @Query("SELECT * FROM whitelist ORDER BY addedAt DESC")
    fun observe(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM whitelist ORDER BY addedAt DESC")
    fun all(): List<VideoEntity>

    @Query("SELECT * FROM whitelist WHERE type = :type AND id = :id LIMIT 1")
    fun find(type: String, id: String): VideoEntity?

    /** -1 when the row already exists (IGNORE), so a second approval changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(row: VideoEntity): Long

    @Query("DELETE FROM whitelist WHERE type = :type AND id = :id")
    fun delete(type: String, id: String): Int
}

@Database(entities = [VideoEntity::class], version = 1, exportSchema = false)
abstract class VideoDatabase : RoomDatabase() {
    abstract fun dao(): VideoDao

    companion object {
        @Volatile private var instance: VideoDatabase? = null

        fun get(c: Context): VideoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(c.applicationContext, VideoDatabase::class.java, "room-videos.db")
                .build().also { instance = it }
        }
    }
}

/**
 * The list on the TV: a Room table in the app's private storage (databases/room-videos.db, not backed up). The calls
 * block, so they are made from the server's threads or a background dispatcher, never the main thread.
 */
class RoomWhitelistStore(private val dao: VideoDao) : WhitelistStore {
    override fun all(): List<WhitelistItem> = dao.all().map { it.toItem() }

    override fun find(kind: String, id: String): WhitelistItem? = dao.find(kind, id)?.toItem()

    override fun insert(item: WhitelistItem): Boolean =
        dao.insert(VideoEntity(item.kind, item.id, item.title, item.addedAt, item.addedBy)) != -1L

    override fun delete(kind: String, id: String): Boolean = dao.delete(kind, id) > 0

    /** The list for the screens; emits again whenever the table changes. */
    fun observe(): Flow<List<WhitelistItem>> = dao.observe().map { rows -> rows.map { it.toItem() } }

    private fun VideoEntity.toItem() = WhitelistItem(type, id, title, addedAt, addedBy)
}
