package io.vena.bosk.drivers.mongo.v2;

import com.mongodb.MongoInterruptedException;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.vena.bosk.exceptions.NotYetImplementedException;
import java.io.Closeable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import org.bson.BsonDocument;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Has three jobs:
 *
 * <ol><li>
 *     does inversion of control on the change stream cursor,
 *     calling {@link ChangeEventListener} callback methods on a background thread,
 * </li><li>
 *     catches event-processing exceptions and reports them to {@link ChangeEventListener#onException}
 *     so the listener can initiate a clean reinitialization and recovery sequence, and
 * </li><li>
 *     acts as a long-lived container for the various transient objects ({@link MongoChangeStreamCursor},
 *     {@link ChangeEventListener}) that get replaced during reinitialization.
 * </li></ol>
 *
 */
@RequiredArgsConstructor
class ChangeEventReceiver implements Closeable {
	private final MongoCollection<Document> collection;
	private final ExecutorService ex = Executors.newFixedThreadPool(1);

	private final Lock lock = new ReentrantLock();
	private volatile State current;
	private volatile BsonDocument lastProcessedResumeToken;
	private volatile Future<?> eventProcessingTask;

	@RequiredArgsConstructor
	private static final class State {
		final MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor;
		final ChangeStreamDocument<Document> initialEvent;
		final ChangeEventListener listener;
	}

	/**
	 * Sets up an event processing loop so that it will start feeding events to
	 * <code>listener</code> when {@link #start()} is called.
	 * No events will be sent to <code>listener</code> before {@link #start()} has been called.
	 *
	 * <p>
	 * Shuts down the existing event processing loop, if any:
	 * this method has been specifically designed to be called more than once,
	 * in case you're wondering why we wouldn't just do this in the constructor.
	 * This method is also designed to support being called on the event-processing
	 * thread itself, since a re-initialization could be triggered by an event or exception.
	 * For example, a {@link ChangeEventListener#onException} implementation can call this.
	 *
	 * @return true if we obtained a resume token.
	 */
	public boolean initialize(ChangeEventListener listener) throws ReceiverInitializationException {
		try {
			lock.lock();
			stop();
			setupNewState(listener);
			return lastProcessedResumeToken != null;
		} catch (RuntimeException | InterruptedException | TimeoutException e) {
			throw new ReceiverInitializationException(e);
		} finally {
			lock.unlock();
		}
	}

	public void start() {
		try {
			lock.lock();
			if (current == null) {
				throw new IllegalStateException("Receiver is not initialized");
			}
			if (eventProcessingTask == null) {
				eventProcessingTask = ex.submit(() -> eventProcessingLoop(current));
			} else {
				LOGGER.debug("Already running");
			}
		} finally {
			lock.unlock();
		}
	}

	public void stop() throws InterruptedException, TimeoutException {
		try {
			lock.lock();
			Future<?> task = this.eventProcessingTask;
			if (task != null) {
				task.cancel(true);
				try {
					task.get(10, SECONDS); // TODO: Config
					LOGGER.warn("Normal completion of event processing task was not expected");
				} catch (CancellationException e) {
					LOGGER.debug("Cancellation succeeded; event loop interrupted");
				}
				this.eventProcessingTask = null;
			}
		} catch (ExecutionException e) {
			throw new NotYetImplementedException("Event processing loop isn't supposed to throw!", e);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void close() {
		try {
			stop();
		} catch (TimeoutException | InterruptedException e) {
			LOGGER.info("Ignoring exception while closing ChangeEventReceiver", e);
		}
		ex.shutdown();
	}

	private void setupNewState(ChangeEventListener newListener) {
		assert this.eventProcessingTask == null;
		this.current = null; // In case any exceptions happen during this method

		MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor;
		ChangeStreamDocument<Document> initialEvent;
		if (lastProcessedResumeToken == null) {
			cursor = collection.watch().cursor();
			initialEvent = cursor.tryNext();
			if (initialEvent == null) {
				// In this case, tryNext() has caused the cursor to point to
				// a token in the past, so we can reliably use that.
				lastProcessedResumeToken = cursor.getResumeToken();
			}
		} else {
			cursor = collection.watch().resumeAfter(lastProcessedResumeToken).cursor();
			initialEvent = null;
		}
		current = new State(cursor, initialEvent, newListener);
	}

	/**
	 * This method has no uncaught exceptions. They're all reported to {@link ChangeEventListener#onException}.
	 */
	private void eventProcessingLoop(State state) {
		try {
			if (state.initialEvent != null) {
				processEvent(state, state.initialEvent);
			}
			while (true) {
				processEvent(state, state.cursor.next());
			}
		} catch (MongoInterruptedException e) {
			LOGGER.debug("Event loop interrupted", e);
			state.listener.onException(e);
		} catch (RuntimeException e) {
			LOGGER.info("Unexpected exception; event loop aborted", e);
			state.listener.onException(e);
		}
	}

	private void processEvent(State state, ChangeStreamDocument<Document> event) {
		state.listener.onEvent(event);
		lastProcessedResumeToken = event.getResumeToken();
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(ChangeEventReceiver.class);
}
