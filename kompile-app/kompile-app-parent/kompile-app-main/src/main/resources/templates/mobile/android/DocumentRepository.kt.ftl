package {{packageName}}.data.repository

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import {{packageName}}.data.db.AppDatabase
import {{packageName}}.data.model.DocumentSource
import {{packageName}}.data.model.SourceType

class DocumentRepository(context: Context) {

    private val database = AppDatabase.getInstance(context)

    fun getAllSources(): Flow<List<DocumentSource>> {
        return database.query(
            androidx.sqlite.db.SimpleSQLiteQuery("SELECT * FROM document_sources ORDER BY importedAt DESC")
        ).let { cursor ->
            // Return as flow from a Room query
            getAllSourcesFlow()
        }
    }

    private fun getAllSourcesFlow(): Flow<List<DocumentSource>> {
        // Use a direct query approach via the database
        return kotlinx.coroutines.flow.flow {
            val sources = getAllSourcesOnce()
            emit(sources)
        }
    }

    suspend fun getAllSourcesOnce(): List<DocumentSource> {
        return database.runInTransaction<List<DocumentSource>> {
            val cursor = database.openHelper.readableDatabase.query(
                "SELECT * FROM document_sources ORDER BY importedAt DESC"
            )
            val sources = mutableListOf<DocumentSource>()
            while (cursor.moveToNext()) {
                sources.add(
                    DocumentSource(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        sourceType = SourceType.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow("sourceType"))
                        ),
                        uri = cursor.getString(cursor.getColumnIndexOrThrow("uri")),
                        chunkCount = cursor.getInt(cursor.getColumnIndexOrThrow("chunkCount")),
                        importedAt = cursor.getLong(cursor.getColumnIndexOrThrow("importedAt")),
                        sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("sizeBytes")),
                        status = cursor.getString(cursor.getColumnIndexOrThrow("status"))
                    )
                )
            }
            cursor.close()
            sources
        }
    }

    suspend fun addSource(source: DocumentSource) {
        database.openHelper.writableDatabase.execSQL(
            """INSERT OR REPLACE INTO document_sources
               (id, name, sourceType, uri, chunkCount, importedAt, sizeBytes, status)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf(
                source.id, source.name, source.sourceType.name,
                source.uri, source.chunkCount, source.importedAt,
                source.sizeBytes, source.status
            )
        )
    }

    suspend fun updateSource(source: DocumentSource) {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE document_sources SET name = ?, sourceType = ?, uri = ?,
               chunkCount = ?, sizeBytes = ?, status = ? WHERE id = ?""",
            arrayOf(
                source.name, source.sourceType.name, source.uri,
                source.chunkCount, source.sizeBytes, source.status, source.id
            )
        )
    }

    suspend fun deleteSource(sourceId: String) {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM document_sources WHERE id = ?",
            arrayOf(sourceId)
        )
    }

    suspend fun getSource(sourceId: String): DocumentSource? {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT * FROM document_sources WHERE id = ?",
            arrayOf(sourceId)
        )
        return if (cursor.moveToFirst()) {
            val source = DocumentSource(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                sourceType = SourceType.valueOf(
                    cursor.getString(cursor.getColumnIndexOrThrow("sourceType"))
                ),
                uri = cursor.getString(cursor.getColumnIndexOrThrow("uri")),
                chunkCount = cursor.getInt(cursor.getColumnIndexOrThrow("chunkCount")),
                importedAt = cursor.getLong(cursor.getColumnIndexOrThrow("importedAt")),
                sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("sizeBytes")),
                status = cursor.getString(cursor.getColumnIndexOrThrow("status"))
            )
            cursor.close()
            source
        } else {
            cursor.close()
            null
        }
    }

    suspend fun getSourceCount(): Int {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM document_sources"
        )
        cursor.moveToFirst()
        val count = cursor.getInt(0)
        cursor.close()
        return count
    }
}
