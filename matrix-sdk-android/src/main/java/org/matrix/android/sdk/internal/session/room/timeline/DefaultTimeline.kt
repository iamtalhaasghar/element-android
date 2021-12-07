/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.timeline

import io.realm.Realm
import io.realm.RealmConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.internal.closeQuietly
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.session.room.membership.LoadRoomMembersTask
import org.matrix.android.sdk.internal.session.sync.handler.room.ReadReceiptHandler
import org.matrix.android.sdk.internal.session.sync.handler.room.ThreadsAwarenessHandler
import org.matrix.android.sdk.internal.task.SemaphoreCoroutineSequencer
import org.matrix.android.sdk.internal.util.createBackgroundHandler
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class DefaultTimeline internal constructor(private val roomId: String,
                                                    private val initialEventId: String?,
                                                    private val realmConfiguration: RealmConfiguration,
                                                    private val loadRoomMembersTask: LoadRoomMembersTask,
                                                    private val readReceiptHandler: ReadReceiptHandler,
                                                    private val settings: TimelineSettings,
                                                    paginationTask: PaginationTask,
                                                    getEventTask: GetContextOfEventTask,
                                                    fetchTokenAndPaginateTask: FetchTokenAndPaginateTask,
                                                    timelineEventMapper: TimelineEventMapper,
                                                    timelineInput: TimelineInput,
                                                    threadsAwarenessHandler: ThreadsAwarenessHandler,
                                                    eventDecryptor: TimelineEventDecryptor) : Timeline {

    companion object {
        val BACKGROUND_HANDLER = createBackgroundHandler("DefaultTimeline_Thread")
    }

    override val timelineID = UUID.randomUUID().toString()

    private val listeners = CopyOnWriteArrayList<Timeline.Listener>()
    private val isStarted = AtomicBoolean(false)
    private val forwardState = AtomicReference(Timeline.PaginationState())
    private val backwardState = AtomicReference(Timeline.PaginationState())

    private val backgroundRealm = AtomicReference<Realm>()
    private val timelineDispatcher = BACKGROUND_HANDLER.asCoroutineDispatcher()
    private val timelineScope = CoroutineScope(SupervisorJob() + timelineDispatcher)
    private val sequencer = SemaphoreCoroutineSequencer()

    private val strategyDependencies = LoadTimelineStrategy.Dependencies(
            timelineScope = timelineScope,
            eventDecryptor = eventDecryptor,
            timelineSettings = settings,
            paginationTask = paginationTask,
            fetchTokenAndPaginateTask = fetchTokenAndPaginateTask,
            timelineInput = timelineInput,
            timelineEventMapper = timelineEventMapper,
            realm = backgroundRealm,
            getContextOfEventTask = getEventTask,
            threadsAwarenessHandler = threadsAwarenessHandler,
            onLimitedTimeline = this::onLimitedTimeline,
            onEventsUpdated = this::postSnapshot,
            onNewTimelineEvents = this::onNewTimelineEvents
    )

    private var strategy: LoadTimelineStrategy = buildStrategy(LoadTimelineStrategy.Mode.Live)

    override val isLive: Boolean
        get() = !getPaginationState(Timeline.Direction.FORWARDS).hasMoreToLoad

    override fun addListener(listener: Timeline.Listener): Boolean {
        listeners.add(listener)
        postSnapshot()
        return true
    }

    override fun removeListener(listener: Timeline.Listener): Boolean {
        return listeners.remove(listener)
    }

    override fun removeAllListeners() {
        listeners.clear()
    }

    override fun start() {
        timelineScope.launch {
            loadRoomMembersIfNeeded()
        }
        timelineScope.launch {
            sequencer.post {
                if (isStarted.compareAndSet(false, true)) {
                    val realm = Realm.getInstance(realmConfiguration)
                    ensureReadReceiptAreLoaded(realm)
                    backgroundRealm.set(realm)
                    openAround(initialEventId)
                }
            }
        }
    }

    override fun dispose() {
        timelineScope.coroutineContext.cancelChildren()
        timelineScope.launch {
            sequencer.post {
                if (isStarted.compareAndSet(true, false)) {
                    strategy.onStop()
                    backgroundRealm.get().closeQuietly()
                }
            }
        }
    }

    override fun restartWithEventId(eventId: String?) {
        timelineScope.launch {
            openAround(eventId)
            postSnapshot()
        }
    }

    override fun hasMoreToLoad(direction: Timeline.Direction): Boolean {
        return getPaginationState(direction).hasMoreToLoad
    }

    override fun paginate(direction: Timeline.Direction, count: Int) {
        timelineScope.launch {
            loadMore(count, direction, fetchOnServerIfNeeded = true)
        }
    }

    override suspend fun awaitPaginate(direction: Timeline.Direction, count: Int): List<TimelineEvent> {
        withContext(timelineDispatcher) {
            loadMore(count, direction, fetchOnServerIfNeeded = true)
        }
        return awaitSnapshot()
    }

    override suspend fun awaitSnapshot(): List<TimelineEvent> = withContext(timelineDispatcher) {
        strategy.buildSnapshot()
    }

    override fun getIndexOfEvent(eventId: String?): Int? {
        if (eventId == null) return null
        return strategy.getBuiltEventIndex(eventId)
    }

    override fun getPaginationState(direction: Timeline.Direction): Timeline.PaginationState {
        return if (direction == Timeline.Direction.BACKWARDS) {
            backwardState
        } else {
            forwardState
        }.get()
    }

    private suspend fun loadMore(count: Int, direction: Timeline.Direction, fetchOnServerIfNeeded: Boolean) {
        val baseLogMessage = "loadMore(count: $count, direction: $direction, roomId: $roomId, fetchOnServer: $fetchOnServerIfNeeded)"
        Timber.v("$baseLogMessage started")
        if (!isStarted.get()) {
            throw IllegalStateException("You should call start before using timeline")
        }
        val currentState = getPaginationState(direction)
        if (!currentState.hasMoreToLoad) {
            Timber.v("$baseLogMessage : nothing more to load")
            return
        }
        if (currentState.loading) {
            Timber.v("$baseLogMessage : already loading")
            return
        }
        updateState(direction) {
            it.copy(loading = true)
        }
        val loadMoreResult = strategy.loadMore(count, direction, fetchOnServerIfNeeded)
        Timber.v("$baseLogMessage: result $loadMoreResult")
        val hasMoreToLoad = loadMoreResult != LoadMoreResult.REACHED_END
        updateState(direction) {
            it.copy(loading = false, hasMoreToLoad = hasMoreToLoad)
        }
    }

    private suspend fun openAround(eventId: String?) = withContext(timelineDispatcher) {
        val baseLogMessage = "openAround(eventId: $eventId)"
        Timber.v("$baseLogMessage started")
        if (!isStarted.get()) {
            throw IllegalStateException("You should call start before using timeline")
        }
        strategy.onStop()
        strategy = if (eventId == null) {
            buildStrategy(LoadTimelineStrategy.Mode.Live)
        } else {
            buildStrategy(LoadTimelineStrategy.Mode.Permalink(eventId))
        }
        initPaginationStates(eventId)
        strategy.onStart()
        loadMore(
                count = strategyDependencies.timelineSettings.initialSize,
                direction = Timeline.Direction.BACKWARDS,
                fetchOnServerIfNeeded = false
        )
    }

    private suspend fun initPaginationStates(eventId: String?) {
        updateState(Timeline.Direction.FORWARDS) {
            it.copy(loading = false, hasMoreToLoad = eventId != null)
        }
        updateState(Timeline.Direction.BACKWARDS) {
            it.copy(loading = false, hasMoreToLoad = true)
        }
    }

    private fun onLimitedTimeline() {
        timelineScope.launch {
            initPaginationStates(null)
            loadMore(settings.initialSize, Timeline.Direction.BACKWARDS, false)
        }
    }

    private fun postSnapshot() {
        timelineScope.launch {
            val snapshot = strategy.buildSnapshot()
            Timber.v("Post snapshot of ${snapshot.size} items")
            withContext(Dispatchers.Main) {
                listeners.forEach {
                    tryOrNull { it.onTimelineUpdated(snapshot) }
                }
            }
        }
    }

    private fun onNewTimelineEvents(eventIds: List<String>) {
        timelineScope.launch(Dispatchers.Main) {
            listeners.forEach {
                tryOrNull { it.onNewTimelineEvents(eventIds) }
            }
        }
    }

    private suspend fun updateState(direction: Timeline.Direction, update: (Timeline.PaginationState) -> Timeline.PaginationState) {
        val stateReference = when (direction) {
            Timeline.Direction.FORWARDS  -> forwardState
            Timeline.Direction.BACKWARDS -> backwardState
        }
        val currentValue = stateReference.get()
        val newValue = update(currentValue)
        stateReference.set(newValue)
        if (newValue != currentValue) {
            postPaginationState(direction, newValue)
        }
    }

    private suspend fun postPaginationState(direction: Timeline.Direction, state: Timeline.PaginationState) = withContext(Dispatchers.Main) {
        Timber.v("Post $direction pagination state: $state ")
        listeners.forEach {
            tryOrNull { it.onStateUpdated(direction, state) }
        }
    }

    private fun buildStrategy(mode: LoadTimelineStrategy.Mode): LoadTimelineStrategy {
        return LoadTimelineStrategy(
                roomId = roomId,
                timelineId = timelineID,
                mode = mode,
                dependencies = strategyDependencies
        )
    }

    private suspend fun loadRoomMembersIfNeeded() {
        val loadRoomMembersParam = LoadRoomMembersTask.Params(roomId)
        try {
            loadRoomMembersTask.execute(loadRoomMembersParam)
        } catch (failure: Throwable) {
            Timber.v("Failed to load room members. Retry in 10s.")
            delay(10_000L)
            loadRoomMembersIfNeeded()
        }
    }

    private fun ensureReadReceiptAreLoaded(realm: Realm) {
        readReceiptHandler.getContentFromInitSync(roomId)
                ?.also {
                    Timber.w("INIT_SYNC Insert when opening timeline RR for room $roomId")
                }
                ?.let { readReceiptContent ->
                    realm.executeTransactionAsync {
                        readReceiptHandler.handle(it, roomId, readReceiptContent, false, null)
                        readReceiptHandler.onContentFromInitSyncHandled(roomId)
                    }
                }
    }
}
