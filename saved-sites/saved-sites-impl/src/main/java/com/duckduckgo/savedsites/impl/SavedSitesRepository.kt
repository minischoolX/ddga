/*
 * Copyright (c) 2023 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.savedsites.impl

import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.global.DefaultDispatcherProvider
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.global.formatters.time.DatabaseDateFormatter
import com.duckduckgo.savedsites.api.SavedSitesRepository
import com.duckduckgo.savedsites.api.models.BookmarkFolder
import com.duckduckgo.savedsites.api.models.BookmarkFolderItem
import com.duckduckgo.savedsites.api.models.FolderBranch
import com.duckduckgo.savedsites.api.models.SavedSite
import com.duckduckgo.savedsites.api.models.SavedSite.Bookmark
import com.duckduckgo.savedsites.api.models.SavedSite.Favorite
import com.duckduckgo.savedsites.api.models.SavedSites
import com.duckduckgo.savedsites.api.models.SavedSitesNames
import com.duckduckgo.savedsites.impl.sync.*
import com.duckduckgo.savedsites.store.Entity
import com.duckduckgo.savedsites.store.EntityType.BOOKMARK
import com.duckduckgo.savedsites.store.EntityType.FOLDER
import com.duckduckgo.savedsites.store.Relation
import com.duckduckgo.savedsites.store.SavedSitesEntitiesDao
import com.duckduckgo.savedsites.store.SavedSitesRelationsDao
import io.reactivex.Single
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

class RealSavedSitesRepository(
    private val savedSitesEntitiesDao: SavedSitesEntitiesDao,
    private val savedSitesRelationsDao: SavedSitesRelationsDao,
    private val savedSitesSettings: SavedSitesSettings,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : SavedSitesRepository {

    private var viewMode = FavoritesViewMode.NATIVE

    init {
        appCoroutineScope.launch(dispatcherProvider.io()) {
            savedSitesSettings.viewModeFlow().collect {
                viewMode = it
            }
        }
    }

    override suspend fun test() {
        when (viewMode) {
            FavoritesViewMode.UNIFIED -> {
                savedSitesSettings.favoritesDisplayMode = FavoritesViewMode.NATIVE
            }
            FavoritesViewMode.NATIVE -> {
                savedSitesSettings.favoritesDisplayMode = FavoritesViewMode.DESKTOP
            }
            else -> {
                savedSitesSettings.favoritesDisplayMode = FavoritesViewMode.UNIFIED
            }
        }
    }

    override fun getSavedSites(folderId: String): Flow<SavedSites> {
        return if (folderId == SavedSitesNames.BOOKMARKS_ROOT) {
            // TODO: why are are we combining favorites with bookmarks?
            getFavorites().combine(getFolderContent(folderId)) { favorites, folderContent ->
                SavedSites(favorites = favorites.distinct(), bookmarks = folderContent.first, folders = folderContent.second)
            }
        } else {
            // TODO: so if I pass FAVORITES_ROOT, I don't get favorites?
            getFolderContent(folderId).map {
                SavedSites(favorites = emptyList(), bookmarks = it.first, folders = it.second)
            }
        }
    }

    private fun getFolderContent(folderId: String): Flow<Pair<List<Bookmark>, List<BookmarkFolder>>> {
        return savedSitesEntitiesDao.entitiesInFolder(folderId).map { entities ->
            val bookmarks = mutableListOf<Bookmark>()
            val folders = mutableListOf<BookmarkFolder>()
            entities.map { entity ->
                mapEntity(entity, folderId, bookmarks, folders)
            }
            Pair(bookmarks.distinct(), folders.distinct())
        }
            .flowOn(dispatcherProvider.io())
    }

    override fun getFolderContentSync(folderId: String): Pair<List<Bookmark>, List<BookmarkFolder>> {
        val entities = savedSitesEntitiesDao.entitiesInFolderSync(folderId)
        val bookmarks = mutableListOf<Bookmark>()
        val folders = mutableListOf<BookmarkFolder>()
        entities.forEach { entity ->
            mapEntity(entity, folderId, bookmarks, folders)
        }
        return Pair(bookmarks.distinct(), folders.distinct())
    }

    override fun getAllFolderContentSync(folderId: String): Pair<List<Bookmark>, List<BookmarkFolder>> {
        val entities = savedSitesEntitiesDao.allEntitiesInFolderSync(folderId)
        val bookmarks = mutableListOf<Bookmark>()
        val folders = mutableListOf<BookmarkFolder>()
        entities.forEach { entity ->
            mapEntity(entity, folderId, bookmarks, folders)
        }
        return Pair(bookmarks.distinct(), folders.distinct())
    }

    private fun mapEntity(
        entity: Entity,
        folderId: String,
        bookmarks: MutableList<Bookmark>,
        folders: MutableList<BookmarkFolder>,
    ) {
        if (entity.type == FOLDER) {
            val numFolders = savedSitesRelationsDao.countEntitiesInFolder(entity.entityId, FOLDER)
            val numBookmarks = savedSitesRelationsDao.countEntitiesInFolder(entity.entityId, BOOKMARK)
            folders.add(BookmarkFolder(entity.entityId, entity.title, folderId, numBookmarks, numFolders, entity.lastModified, entity.deletedFlag()))
        } else {
            bookmarks.add(
                Bookmark(
                    id = entity.entityId,
                    title = entity.title,
                    url = entity.url.orEmpty(),
                    parentId = folderId,
                    lastModified = entity.lastModified,
                ),
            )
        }
    }

    override fun getFolderTree(
        selectedFolderId: String,
        currentFolder: BookmarkFolder?,
    ): List<BookmarkFolderItem> {
        val rootFolder = getFolder(SavedSitesNames.BOOKMARKS_ROOT)
        return if (rootFolder != null) {
            val rootFolderItem = BookmarkFolderItem(0, rootFolder, rootFolder.id == selectedFolderId)
            val folders = mutableListOf(rootFolderItem)
            val folderDepth = traverseFolderWithDepth(1, folders, SavedSitesNames.BOOKMARKS_ROOT, selectedFolderId = selectedFolderId, currentFolder)
            if (currentFolder != null) {
                folderDepth.filterNot { it.bookmarkFolder == currentFolder }
            } else {
                folderDepth
            }
        } else {
            emptyList()
        }
    }

    override fun getBookmarksTree(): List<Bookmark> {
        val bookmarks = mutableListOf<Bookmark>()
        val bookmarkEntities = savedSitesEntitiesDao.entitiesByTypeSync(BOOKMARK)
        bookmarkEntities.forEach {
            val relation = savedSitesRelationsDao.relationByEntityId(it.entityId)
            if (relation != null) {
                bookmarks.add(it.mapToBookmark(relation.folderId))
            }
        }
        return bookmarks
    }

    private fun traverseFolderWithDepth(
        depth: Int = 0,
        folders: MutableList<BookmarkFolderItem>,
        folderId: String,
        selectedFolderId: String,
        currentFolder: BookmarkFolder?,
    ): List<BookmarkFolderItem> {
        getFolders(folderId).map {
            if (it.id != currentFolder?.id) {
                folders.add(BookmarkFolderItem(depth, it, it.id == selectedFolderId))
                traverseFolderWithDepth(depth + 1, folders, it.id, selectedFolderId = selectedFolderId, currentFolder)
            }
        }
        return folders
    }

    override fun insertFolderBranch(branchToInsert: FolderBranch) {
        with(branchToInsert) {
            folders.forEach {
                insert(it)
            }
            bookmarks.forEach {
                insert(it)
            }
        }
    }

    override fun getFolderBranch(folder: BookmarkFolder): FolderBranch {
        val bookmarks = mutableListOf<Bookmark>()
        val folders = mutableListOf(folder)
        val folderContent = traverseBranch(bookmarks, folders, folder.id)
        return FolderBranch(folderContent.first, folderContent.second)
    }

    private fun traverseBranch(
        bookmarks: MutableList<Bookmark>,
        folders: MutableList<BookmarkFolder>,
        folderId: String,
    ): Pair<List<Bookmark>, List<BookmarkFolder>> {
        val folderContent = folderContent(folderId)
        bookmarks.addAll(folderContent.first)
        folders.addAll(folderContent.second)
        folderContent.second.forEach {
            traverseBranch(bookmarks, folders, it.id)
        }
        return Pair(bookmarks, folders)
    }

    private fun getFolders(folderId: String): List<BookmarkFolder> {
        return savedSitesEntitiesDao.entitiesInFolder(folderId, FOLDER).map { entity ->
            entity.mapToBookmarkFolder(folderId, savedSitesRelationsDao)
        }
    }

    private fun folderContent(folderId: String): Pair<List<Bookmark>, List<BookmarkFolder>> {
        val bookmarks = mutableListOf<Bookmark>()
        val folders = mutableListOf<BookmarkFolder>()
        savedSitesEntitiesDao.entitiesInFolderSync(folderId).forEach { entity ->
            if (entity.type == FOLDER) {
                folders.add(entity.mapToBookmarkFolder(folderId, savedSitesRelationsDao))
            } else {
                bookmarks.add(entity.mapToBookmark(folderId))
            }
        }
        return Pair(bookmarks, folders)
    }

    override fun deleteFolderBranch(folder: BookmarkFolder): FolderBranch {
        val folderContent = getFolderBranch(folder)
        folderContent.folders.forEach {
            delete(it)
        }
        folderContent.bookmarks.forEach {
            delete(it)
        }

        delete(folder)
        return folderContent
    }

    override fun getFavorites(): Flow<List<Favorite>> {
        return savedSitesSettings.viewModeFlow().flatMapLatest { viewMode ->
            val favoriteFolder = getQueryFavoriteFolder(viewMode)
            savedSitesRelationsDao.relations(favoriteFolder).distinctUntilChanged().map { relations ->
                Timber.i("Sync-Feature: getFavorites as Flow, emit relations")
                relations.mapIndexed { index, relation ->
                    savedSitesEntitiesDao.entityById(relation.entityId)!!.mapToFavorite(index)
                }
            }.flowOn(dispatcherProvider.io())
        }
    }

    // TODO: update this one for autocomplete
    override fun getFavoritesObservable(): Single<List<Favorite>> {
        return savedSitesRelationsDao.relationsObservable(SavedSitesNames.FAVORITES_ROOT).map { relations ->
            relations.mapIndexed { index, relation ->
                savedSitesEntitiesDao.entityById(relation.entityId)!!.mapToFavorite(index)
            }
        }
    }

    override fun getFavoritesSync(): List<Favorite> {
        val folder = getQueryFavoriteFolder(viewMode)
        return savedSitesEntitiesDao.entitiesInFolderSync(folder).mapToFavorites()
    }

    override fun getFavoritesCountByDomain(domain: String): Int {
        val folder = getQueryFavoriteFolder(viewMode)
        return savedSitesRelationsDao.countFavouritesByUrl(domain, folder)
    }

    override fun getFavorite(url: String): Favorite? {
        val folder = getQueryFavoriteFolder(viewMode)
        val favorites = savedSitesEntitiesDao.entitiesInFolderSync(folder).mapIndexed { index, entity ->
            entity.mapToFavorite(index)
        }
        return favorites.firstOrNull { it.url == url }
    }

    override fun getFavoriteById(id: String): Favorite? {
        val folder = getQueryFavoriteFolder(viewMode)
        val favorites = savedSitesEntitiesDao.entitiesInFolderSync(folder).mapIndexed { index, entity ->
            entity.mapToFavorite(index)
        }
        return favorites.firstOrNull { it.id == id }
    }

    override fun getBookmarks(): Flow<List<Bookmark>> {
        return savedSitesEntitiesDao.entitiesByType(BOOKMARK).map { entities ->
            entities.map { entity ->
                val relationId = savedSitesRelationsDao.relationByEntityId(entity.entityId)?.folderId ?: SavedSitesNames.BOOKMARKS_ROOT
                entity.mapToBookmark(relationId)
            }
        }
    }

    override fun getBookmarksObservable(): Single<List<Bookmark>> {
        return savedSitesEntitiesDao.entitiesByTypeObservable(BOOKMARK).map { entities ->
            entities.map { entity ->
                val relationId = savedSitesRelationsDao.relationByEntityId(entity.entityId)?.folderId ?: SavedSitesNames.BOOKMARKS_ROOT
                entity.mapToBookmark(relationId)
            }
        }
    }

    override fun getBookmark(url: String): Bookmark? {
        val bookmark = savedSitesEntitiesDao.entityByUrl(url)
        return if (bookmark != null) {
            savedSitesRelationsDao.relationByEntityId(bookmark.entityId)?.let {
                bookmark.mapToBookmark(it.folderId)
            }
        } else {
            null
        }
    }

    override fun getBookmarkById(id: String): Bookmark? {
        val bookmark = savedSitesEntitiesDao.entityById(id)
        return if (bookmark != null) {
            savedSitesRelationsDao.relationByEntityId(bookmark.entityId)?.let {
                bookmark.mapToBookmark(it.folderId)
            }
        } else {
            null
        }
    }

    // inspect how this is used
    // favorite is a bookmark, seems we prioritize being a favorite
    override fun getSavedSite(id: String): SavedSite? {
        val savedSite = savedSitesEntitiesDao.entityById(id)
        return if (savedSite != null) {
            getFavoriteById(id) ?: getBookmarkById(id)
        } else {
            null
        }
    }

    override fun hasBookmarks(): Boolean {
        return bookmarksCount() > 0
    }

    override fun hasFavorites(): Boolean {
        return favoritesCount() > 0
    }

    override fun insertBookmark(
        url: String,
        title: String,
    ): Bookmark {
        val titleOrFallback = title.takeIf { it.isNotEmpty() } ?: url
        val entity = Entity(title = titleOrFallback, url = url, type = BOOKMARK)
        savedSitesEntitiesDao.insert(entity)
        savedSitesRelationsDao.insert(Relation(folderId = SavedSitesNames.BOOKMARKS_ROOT, entityId = entity.entityId))
        savedSitesEntitiesDao.updateModified(SavedSitesNames.BOOKMARKS_ROOT, entity.lastModified!!)

        return entity.mapToBookmark(SavedSitesNames.BOOKMARKS_ROOT)
    }

    override fun insertFavorite(
        id: String,
        url: String,
        title: String,
        lastModified: String?,
    ): Favorite {
        val idOrFallback = id.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val titleOrFallback = title.takeIf { it.isNotEmpty() } ?: url
        val existentBookmark = savedSitesEntitiesDao.entityByUrl(url)
        val lastModifiedOrFallback = lastModified ?: DatabaseDateFormatter.iso8601()
        val favoriteFolders = getInsertFavoriteFolder(viewMode)
        return if (existentBookmark == null) {
            val entity = Entity(entityId = idOrFallback, title = titleOrFallback, url = url, type = BOOKMARK, lastModified = lastModifiedOrFallback)
            savedSitesEntitiesDao.insert(entity)
            savedSitesRelationsDao.insert(Relation(folderId = SavedSitesNames.BOOKMARKS_ROOT, entityId = entity.entityId))
            savedSitesEntitiesDao.updateModified(SavedSitesNames.BOOKMARKS_ROOT, lastModifiedOrFallback)
            favoriteFolders.forEach { favoriteFolderName ->
                savedSitesRelationsDao.insert(Relation(folderId = favoriteFolderName, entityId = entity.entityId))
                savedSitesEntitiesDao.updateModified(entityId = favoriteFolderName, lastModified = lastModifiedOrFallback)
            }
            getFavoriteById(entity.entityId)!!
        } else {
            favoriteFolders.forEach { favoriteFolderName ->
                savedSitesRelationsDao.insert(Relation(folderId = favoriteFolderName, entityId = existentBookmark.entityId))
                savedSitesEntitiesDao.updateModified(entityId = favoriteFolderName, lastModified = lastModifiedOrFallback)
            }
            savedSitesEntitiesDao.updateModified(id, lastModifiedOrFallback)
            getFavorite(url)!!
        }
    }

    override fun insert(savedSite: SavedSite): SavedSite {
        Timber.d("Sync-Feature: inserting Saved Site $savedSite")
        val titleOrFallback = savedSite.titleOrFallback()
        return when (savedSite) {
            is Favorite -> {
                return insertFavorite(savedSite.id, savedSite.url, savedSite.title, savedSite.lastModified)
            }

            is Bookmark -> {
                // a bookmark will have a parent folder that we must respect
                val entity = Entity(
                    entityId = savedSite.id,
                    title = titleOrFallback,
                    url = savedSite.url,
                    type = BOOKMARK,
                    lastModified = savedSite.lastModified ?: DatabaseDateFormatter.iso8601(),
                )
                savedSitesEntitiesDao.insert(entity)
                savedSitesRelationsDao.insert(Relation(folderId = savedSite.parentId, entityId = entity.entityId))
                savedSitesEntitiesDao.updateModified(savedSite.parentId, savedSite.lastModified ?: DatabaseDateFormatter.iso8601())
                entity.mapToBookmark(savedSite.parentId)
            }
        }
    }

    private fun getQueryFavoriteFolder(viewMode: FavoritesViewMode): String {
        return when (viewMode) {
            FavoritesViewMode.NATIVE -> SavedSitesNames.FAVORITES_MOBILE_ROOT
            FavoritesViewMode.UNIFIED -> SavedSitesNames.FAVORITES_ROOT
            FavoritesViewMode.DESKTOP -> SavedSitesNames.FAVORITES_DESKTOP_ROOT
        }
    }

    private fun getInsertFavoriteFolder(viewMode: FavoritesViewMode): List<String> {
        return when (viewMode) {
            FavoritesViewMode.NATIVE -> listOf(SavedSitesNames.FAVORITES_MOBILE_ROOT, SavedSitesNames.FAVORITES_ROOT)
            FavoritesViewMode.UNIFIED -> listOf(SavedSitesNames.FAVORITES_MOBILE_ROOT, SavedSitesNames.FAVORITES_ROOT)
            FavoritesViewMode.DESKTOP -> TODO()
        }
    }

    private fun getDeleteFavoriteFolder(
        entityId: String,
        viewMode: FavoritesViewMode
    ): List<String> {
        return when (viewMode) {
            FavoritesViewMode.NATIVE -> {
                val isDesktopFavorite = savedSitesRelationsDao.relationsByEntityId(entityId)
                    .find { it.folderId == SavedSitesNames.FAVORITES_DESKTOP_ROOT } != null
                if (isDesktopFavorite) {
                    listOf(SavedSitesNames.FAVORITES_MOBILE_ROOT)
                } else {
                    listOf(SavedSitesNames.FAVORITES_MOBILE_ROOT, SavedSitesNames.FAVORITES_ROOT)
                }
            }

            FavoritesViewMode.UNIFIED -> {
                return listOf(SavedSitesNames.FAVORITES_MOBILE_ROOT, SavedSitesNames.FAVORITES_ROOT, SavedSitesNames.FAVORITES_DESKTOP_ROOT)
            }

            FavoritesViewMode.DESKTOP -> TODO()
        }
    }

    override fun delete(savedSite: SavedSite) {
        when (savedSite) {
            is Bookmark -> deleteBookmark(savedSite)
            is Favorite -> deleteFavorite(savedSite)
        }
    }

    private fun deleteFavorite(favorite: Favorite) {
        val deleteFavoriteFolder = getDeleteFavoriteFolder(favorite.id, viewMode)
        deleteFavoriteFolder.forEach { folderName ->
            savedSitesRelationsDao.deleteRelationByEntity(favorite.id, folderName)
            savedSitesEntitiesDao.updateModified(folderName)
        }
    }

    private fun deleteBookmark(bookmark: Bookmark) {
        val folders = savedSitesRelationsDao.relationsByEntityId(bookmark.id)

        savedSitesRelationsDao.deleteRelationByEntity(bookmark.id)
        savedSitesEntitiesDao.delete(bookmark.id)

        folders.forEach {
            savedSitesEntitiesDao.updateModified(it.folderId)
        }
    }

    // This is called only to update content, not position
    override fun updateFavourite(favorite: Favorite) {
        savedSitesEntitiesDao.entityById(favorite.id)?.let { entity ->
            savedSitesEntitiesDao.update(Entity(entity.entityId, favorite.title, favorite.url, BOOKMARK))
        }
    }

    override fun updateBookmark(
        bookmark: Bookmark,
        fromFolderId: String,
    ) {
        if (bookmark.parentId != fromFolderId) {
            // bookmark has moved to another folder
            savedSitesRelationsDao.deleteRelationByEntityAndFolder(bookmark.id, fromFolderId)
            savedSitesRelationsDao.insert(Relation(folderId = bookmark.parentId, entityId = bookmark.id))
        }

        val lastModified = DatabaseDateFormatter.iso8601()
        savedSitesEntitiesDao.update(
            Entity(
                bookmark.id,
                bookmark.title,
                bookmark.url,
                BOOKMARK,
                lastModified,
            ),
        )
        savedSitesEntitiesDao.updateModified(fromFolderId, lastModified)
        savedSitesEntitiesDao.updateModified(bookmark.parentId, lastModified)
    }

    override fun insert(folder: BookmarkFolder): BookmarkFolder {
        val entity = Entity(
            entityId = folder.id,
            title = folder.name,
            url = "",
            lastModified = folder.lastModified ?: DatabaseDateFormatter.iso8601(),
            type = FOLDER,
        )
        savedSitesEntitiesDao.insert(entity)
        savedSitesRelationsDao.insert(Relation(folderId = folder.parentId, entityId = folder.id))
        savedSitesEntitiesDao.updateModified(folder.parentId, folder.lastModified ?: DatabaseDateFormatter.iso8601())
        return folder
    }

    override fun update(folder: BookmarkFolder) {
        val oldFolder = getFolder(folder.id) ?: return

        savedSitesEntitiesDao.update(
            Entity(
                entityId = folder.id,
                title = folder.name,
                url = "",
                type = FOLDER,
                lastModified = DatabaseDateFormatter.iso8601(),
            ),
        )

        // has folder parent changed?
        if (oldFolder.parentId != folder.parentId) {
            savedSitesRelationsDao.deleteRelationByEntity(folder.id)
            savedSitesRelationsDao.insert(Relation(folderId = folder.parentId, entityId = folder.id))
            savedSitesEntitiesDao.updateModified(folder.parentId)
            savedSitesEntitiesDao.updateModified(oldFolder.parentId)
        }
    }

    override fun replaceFolderContent(
        folder: BookmarkFolder,
        oldId: String,
    ) {
        savedSitesEntitiesDao.updateId(oldId, folder.id)
        savedSitesRelationsDao.updateEntityId(oldId, folder.id)
        savedSitesRelationsDao.updateFolderId(oldId, folder.id)

        val oldFolder = getFolder(folder.id) ?: return

        savedSitesEntitiesDao.update(
            Entity(
                entityId = folder.id,
                title = folder.name,
                url = "",
                type = FOLDER,
                lastModified = folder.lastModified ?: DatabaseDateFormatter.iso8601(),
            ),
        )

        // has folder parent changed?
        if (folder.parentId.isNotEmpty() && oldFolder.parentId != folder.parentId) {
            savedSitesRelationsDao.deleteRelationByEntity(folder.id)
            savedSitesRelationsDao.insert(Relation(folderId = folder.parentId, entityId = folder.id))
        }
    }

    override fun replaceBookmark(
        bookmark: Bookmark,
        localId: String,
    ) {
        // check if bookmark has moved
        val storedBookmark = getBookmarkById(bookmark.id)
        if (storedBookmark != null) {
            if (storedBookmark.parentId != bookmark.parentId) {
                // bookmark has moved to another folder
                Timber.d("Sync-Feature: ${bookmark.id} has moved from folder ${storedBookmark.parentId} to ${bookmark.parentId}")
                savedSitesRelationsDao.deleteRelationByEntityAndFolder(bookmark.id, storedBookmark.parentId)
                savedSitesRelationsDao.insert(Relation(folderId = bookmark.parentId, entityId = bookmark.id))
            }
        }

        savedSitesEntitiesDao.updateId(localId, bookmark.id)
        savedSitesRelationsDao.updateEntityId(localId, bookmark.id)
        savedSitesEntitiesDao.update(
            Entity(
                bookmark.id,
                bookmark.title,
                bookmark.url,
                BOOKMARK,
                bookmark.lastModified ?: DatabaseDateFormatter.iso8601(),
            ),
        )

        savedSitesEntitiesDao.updateModified(bookmark.parentId, bookmark.lastModified ?: DatabaseDateFormatter.iso8601())
    }

    override fun delete(folder: BookmarkFolder) {
        val relations = savedSitesEntitiesDao.entitiesInFolderSync(folder.id)
        val entities = mutableListOf<Entity>()
        relations.forEach {
            entities.add(it)
        }

        savedSitesRelationsDao.deleteRelationByEntity(folder.id)
        savedSitesRelationsDao.delete(folder.id)
        entities.forEach {
            savedSitesEntitiesDao.delete(it.entityId)
        }
        savedSitesEntitiesDao.delete(folder.id)
        savedSitesEntitiesDao.updateModified(folder.parentId)
    }

    override fun getFolder(folderId: String): BookmarkFolder? {
        val entity = savedSitesEntitiesDao.entityById(folderId)
        val relation = savedSitesRelationsDao.relationByEntityId(folderId)

        return if (entity != null) {
            val numFolders = savedSitesRelationsDao.countEntitiesInFolder(entity.entityId, FOLDER)
            val numBookmarks = savedSitesRelationsDao.countEntitiesInFolder(entity.entityId, BOOKMARK)
            BookmarkFolder(
                folderId,
                entity.title,
                relation?.folderId ?: "",
                numFolders = numFolders,
                numBookmarks = numBookmarks,
                lastModified = entity.lastModified,
                deleted = entity.deletedFlag(),
            )
        } else {
            null
        }
    }

    override fun getFolderByName(folderName: String): BookmarkFolder? {
        val entity = savedSitesEntitiesDao.entityByName(folderName)
        return if (entity != null) {
            val relation = savedSitesRelationsDao.relationByEntityId(entity.entityId)
            val numFolders = savedSitesRelationsDao.countEntitiesInFolder(entity.entityId, FOLDER)
            val numBookmarks = savedSitesRelationsDao.countEntitiesInFolder(entity.entityId, BOOKMARK)
            BookmarkFolder(
                entity.entityId,
                entity.title,
                relation?.folderId ?: "",
                numFolders = numFolders,
                numBookmarks = numBookmarks,
                lastModified = entity.lastModified,
                deleted = entity.deletedFlag(),
            )
        } else {
            null
        }
    }

    override fun deleteAll() {
        savedSitesRelationsDao.deleteAll()
        savedSitesEntitiesDao.deleteAll()
    }

    override fun bookmarksCount(): Long {
        return savedSitesEntitiesDao.entitiesByTypeSync(BOOKMARK).size.toLong()
    }

    override fun favoritesCount(): Long {
        val folder = getQueryFavoriteFolder(viewMode)
        return savedSitesEntitiesDao.entitiesInFolderSync(folder).size.toLong()
    }

    override fun lastModified(): Flow<String> {
        return savedSitesEntitiesDao.lastModified().map { it.entityId }
    }

    override fun getFoldersModifiedSince(since: String): List<BookmarkFolder> {
        val folders = savedSitesEntitiesDao.allEntitiesByTypeSync(FOLDER).filter { it.modifiedSince(since) }
        Timber.d("Sync-Feature: folders modified since $since are $folders")
        return folders.map { mapBookmarkFolder(it) }
    }

    private fun mapBookmarkFolder(entity: Entity): BookmarkFolder {
        val relation = savedSitesRelationsDao.relationByEntityId(entity.entityId)
        val numFolders = savedSitesRelationsDao.countEntitiesInFolder(entity.entityId, FOLDER)
        val numBookmarks = savedSitesRelationsDao.countEntitiesInFolder(entity.entityId, BOOKMARK)
        val lastModified = entity.lastModified ?: null
        return BookmarkFolder(
            entity.entityId,
            entity.title,
            relation?.folderId ?: "",
            numFolders = numFolders,
            numBookmarks = numBookmarks,
            lastModified = lastModified,
            deleted = entity.deletedFlag(),
        )
    }

    override fun getBookmarksModifiedSince(since: String): List<Bookmark> {
        val bookmarks = savedSitesEntitiesDao.allEntitiesByTypeSync(BOOKMARK).filter { it.modifiedSince(since) }
        Timber.d("Sync-Feature: bookmarks modified since $since are $bookmarks")
        return bookmarks.map { mapToBookmark(it)!! }
    }

    override fun pruneDeleted() {
        Timber.d("Sync-Feature: pruning soft deleted entities")
        savedSitesEntitiesDao.allDeleted().forEach {
            savedSitesRelationsDao.deleteRelationByEntity(it.entityId)
            savedSitesEntitiesDao.deletePermanently(it)
        }
    }

    override fun updateModifiedSince(
        originalDate: String,
        modifiedSince: String,
    ) {
        Timber.d("Sync-Feature: updating entities modified before $originalDate to $modifiedSince")
        val bookmarks = savedSitesEntitiesDao.allEntitiesByTypeSync(BOOKMARK)
            .filterNot { it.modifiedSince(originalDate) }
            .filterNot { it.lastModified.isNullOrEmpty() }
        bookmarks.forEach { bookmark ->
            Timber.i("Sync-Feature: updating bookmark ${bookmark.entityId} from ${bookmark.lastModified} to $modifiedSince")
            savedSitesEntitiesDao.updateModified(bookmark.entityId, modifiedSince)
        }

        val folders = savedSitesEntitiesDao.allEntitiesByTypeSync(FOLDER)
            .filterNot { it.modifiedSince(originalDate) }
            .filterNot { it.lastModified.isNullOrEmpty() }
        folders.forEach { folder ->
            Timber.i("Sync-Feature: updating bookmark ${folder.entityId} from ${folder.lastModified} to $modifiedSince")
            savedSitesEntitiesDao.updateModified(folder.entityId, modifiedSince)
        }
    }

    private fun mapToBookmark(entity: Entity): Bookmark? {
        val relation = savedSitesRelationsDao.relationByEntityId(entity.entityId)
        return if (relation != null) {
            entity.mapToBookmark(relation.folderId)
        } else {
            // bookmark was deleted, we can make the parent id up
            entity.mapToBookmark(SavedSitesNames.BOOKMARKS_ROOT)
        }
    }

    override fun updateWithPosition(favorites: List<Favorite>) {
        val favoriteFolder = getQueryFavoriteFolder(viewMode)
        savedSitesRelationsDao.delete(favoriteFolder)
        val relations = favorites.map { Relation(folderId = favoriteFolder, entityId = it.id) }
        savedSitesRelationsDao.insertList(relations)
        savedSitesEntitiesDao.updateModified(favoriteFolder)
    }
}
