package com.smartdocs.editor.core.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recent_docs")
data class RecentDoc(
    @PrimaryKey val uri: String,
    val name: String,
    val size: Long,
    val lastOpened: Long,
    val lastPage: Int = 0,
    val pageCount: Int = 0,
    val favorite: Boolean = false,
    val thumbPath: String? = null,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class Tag(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val color: Long)

@Entity(tableName = "doc_tags", primaryKeys = ["uri", "tagId"])
data class DocTag(val uri: String, val tagId: Long)

@Entity(tableName = "signatures")
data class SavedSignature(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val isInitials: Boolean = false,
    val created: Long = System.currentTimeMillis(),
)

@Entity(tableName = "reading_bookmarks")
data class ReadingBookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val page: Int,
    val title: String,
    val created: Long = System.currentTimeMillis(),
)

@Entity(tableName = "fill_profile")
data class FillProfileField(@PrimaryKey val key: String, val label: String, val value: String, val order: Int)

@Entity(tableName = "custom_stamps")
data class CustomStamp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String?,
    val imagePath: String?,
    val color: Long,
)

@Dao
interface DocDao {
    @Query("SELECT * FROM recent_docs ORDER BY lastOpened DESC LIMIT 50")
    fun recents(): Flow<List<RecentDoc>>

    @Query("SELECT * FROM recent_docs WHERE favorite = 1 ORDER BY lastOpened DESC")
    fun favorites(): Flow<List<RecentDoc>>

    @Query("SELECT * FROM recent_docs WHERE uri = :uri")
    suspend fun get(uri: String): RecentDoc?

    @Upsert
    suspend fun upsert(doc: RecentDoc)

    @Query("UPDATE recent_docs SET lastPage = :page WHERE uri = :uri")
    suspend fun updatePage(uri: String, page: Int)

    @Query("UPDATE recent_docs SET favorite = :fav WHERE uri = :uri")
    suspend fun setFavorite(uri: String, fav: Boolean)

    @Query("DELETE FROM recent_docs WHERE uri = :uri")
    suspend fun remove(uri: String)

    @Query("DELETE FROM recent_docs")
    suspend fun clear()

    /** Audit DI-22: a renamed / moved document keeps its recents row (last page, favourite, thumbnail). */
    @Query("UPDATE OR REPLACE recent_docs SET uri = :to, name = :name WHERE uri = :from")
    suspend fun moveUri(from: String, to: String, name: String)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name")
    fun tags(): Flow<List<Tag>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: Tag): Long

    @Delete
    suspend fun delete(tag: Tag)

    @Query("SELECT t.* FROM tags t INNER JOIN doc_tags d ON d.tagId = t.id WHERE d.uri = :uri")
    fun tagsForDoc(uri: String): Flow<List<Tag>>

    @Query("SELECT r.* FROM recent_docs r INNER JOIN doc_tags d ON d.uri = r.uri WHERE d.tagId = :tagId ORDER BY r.lastOpened DESC")
    fun docsForTag(tagId: Long): Flow<List<RecentDoc>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attach(docTag: DocTag)

    @Delete
    suspend fun detach(docTag: DocTag)

    /** Audit DI-22: re-key a document's tags; tags the target already has stay once (see [removeDoc]). */
    @Query("UPDATE OR IGNORE doc_tags SET uri = :to WHERE uri = :from")
    suspend fun moveUri(from: String, to: String)

    @Query("DELETE FROM doc_tags WHERE uri = :uri")
    suspend fun removeDoc(uri: String)
}

@Dao
interface SignatureDao {
    @Query("SELECT * FROM signatures ORDER BY created DESC")
    fun all(): Flow<List<SavedSignature>>

    @Insert
    suspend fun insert(sig: SavedSignature): Long

    @Delete
    suspend fun delete(sig: SavedSignature)

    /** nitro-fillsign2: temporary signatures are removed by path at the next start. */
    @Query("DELETE FROM signatures WHERE path = :path")
    suspend fun deleteByPath(path: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM reading_bookmarks WHERE uri = :uri ORDER BY page")
    fun forDoc(uri: String): Flow<List<ReadingBookmark>>

    @Insert
    suspend fun insert(b: ReadingBookmark): Long

    @Delete
    suspend fun delete(b: ReadingBookmark)

    /** Audit DI-22: bookmarks follow a renamed / moved document. */
    @Query("UPDATE reading_bookmarks SET uri = :to WHERE uri = :from")
    suspend fun moveUri(from: String, to: String)

    @Query("DELETE FROM reading_bookmarks WHERE uri = :uri")
    suspend fun removeDoc(uri: String)
}

@Dao
interface FillProfileDao {
    @Query("SELECT * FROM fill_profile ORDER BY `order`")
    fun all(): Flow<List<FillProfileField>>

    @Upsert
    suspend fun upsert(field: FillProfileField)

    @Delete
    suspend fun delete(field: FillProfileField)
}

@Dao
interface StampDao {
    @Query("SELECT * FROM custom_stamps ORDER BY id DESC")
    fun all(): Flow<List<CustomStamp>>

    @Insert
    suspend fun insert(stamp: CustomStamp): Long

    @Delete
    suspend fun delete(stamp: CustomStamp)
}

@Database(
    entities = [RecentDoc::class, Tag::class, DocTag::class, SavedSignature::class, ReadingBookmark::class, FillProfileField::class, CustomStamp::class],
    version = 1,
    // audit DI-08: schemas are exported to app/schemas (committed) so every future version gets a real Migration
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun docs(): DocDao
    abstract fun tags(): TagDao
    abstract fun signatures(): SignatureDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun fillProfile(): FillProfileDao
    abstract fun stamps(): StampDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "smartdocs.db")
                // audit DI-08: an upgrade without a Migration must fail loudly in testing, never wipe recents, tags,
                // bookmarks, the fill profile and signatures. Only a downgrade (debug installs) may start empty.
                // When bumping `version`: add a Migration here and a MigrationTestHelper test against app/schemas.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
