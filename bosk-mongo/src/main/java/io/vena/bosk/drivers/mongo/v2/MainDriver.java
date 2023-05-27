package io.vena.bosk.drivers.mongo.v2;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.drivers.mongo.BsonPlugin;
import io.vena.bosk.drivers.mongo.MongoDriver;
import io.vena.bosk.drivers.mongo.MongoDriverSettings;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.bson.BsonDocument;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.vena.bosk.drivers.mongo.v2.Formatter.REVISION_ONE;

public class MainDriver<R extends Entity> implements MongoDriver<R> {
	private final Bosk<R> bosk;
	private final MongoDriverSettings driverSettings;
	private final BsonPlugin bsonPlugin;
	private final BoskDriver<R> downstream;

	private final Reference<R> rootRef;
	private final MongoClient mongoClient;
	private final MongoDatabase database;
	private final MongoCollection<Document> collection;
	private final ChangeEventReceiver receiver;

	private final Lock initializationLock = new ReentrantLock();
	private volatile FormatDriver<R> formatDriver = new DisconnectedDriver<>();
	private volatile boolean isClosed = false;

	public MainDriver(
		Bosk<R> bosk,
		MongoClientSettings clientSettings,
		MongoDriverSettings driverSettings,
		BsonPlugin bsonPlugin,
		BoskDriver<R> downstream
		) {
		validateMongoClientSettings(clientSettings);

		this.bosk = bosk;
		this.driverSettings = driverSettings;
		this.bsonPlugin = bsonPlugin;
		this.downstream = downstream;

		this.rootRef = bosk.rootReference();
		this.mongoClient = MongoClients.create(clientSettings);
		this.database = mongoClient
			.getDatabase(driverSettings.database());
		this.collection = database
			.getCollection(COLLECTION_NAME);
		this.receiver = new ChangeEventReceiver(bosk.name(), collection);
	}

	private void validateMongoClientSettings(MongoClientSettings clientSettings) {
		// We require ReadConcern and WriteConcern to be MAJORITY to ensure the Causal Consistency
		// guarantees needed to meet the requirements of the BoskDriver interface.
		// https://www.mongodb.com/docs/manual/core/causal-consistency-read-write-concerns/

		if (clientSettings.getReadConcern() != ReadConcern.MAJORITY) {
			throw new IllegalArgumentException("MongoDriver requires MongoClientSettings to specify ReadConcern.MAJORITY");
		}
		if (clientSettings.getWriteConcern() != WriteConcern.MAJORITY) {
			throw new IllegalArgumentException("MongoDriver requires MongoClientSettings to specify WriteConcern.MAJORITY");
		}
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		logDriverOperation("initialRoot");
		// TODO: How to initialize the database and collection if they don't exist?
		R result;
		try {
			result = initializeReplication();
		} catch (UninitializedCollectionException e) {
			if (LOGGER.isTraceEnabled()) {
				LOGGER.trace("Initializing collection", e);
			} else {
				LOGGER.info("Initializing collection");
			}
			FormatDriver<R> newDriver = newSingleDocFormatDriver(); // TODO: Pick based on config?
			result = downstream.initialRoot(rootType);
			newDriver.initializeCollection(new StateAndMetadata<>(result, REVISION_ONE));
			formatDriver = newDriver;
		}
		receiver.start();
		if (result == null) {
			return downstream.initialRoot(rootType);
		} else {
			return result;
		}
	}

	private void recoverFrom(Exception exception) {
		LOGGER.error("Recovering from unexpected exception; reinitializing", exception);
		R result;
		try {
			result = initializeReplication();
		} catch (UninitializedCollectionException e) {
			LOGGER.warn("Collection is uninitialized; driver is disconnected", e);
			return;
		}
		if (result != null) {
			// Because we haven't called receiver.start() yet, this won't race with other events
			downstream.submitReplacement(rootRef, result);
		}
		if (!isClosed) {
			receiver.start();
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		logDriverOperation("submitReplacement({})", target);
		formatDriver.submitReplacement(target, newValue);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		logDriverOperation("submitConditionalReplacement({}, {} = {})", target, precondition, requiredValue);
		formatDriver.submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		logDriverOperation("submitInitialization({})", target);
		formatDriver.submitInitialization(target, newValue);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		logDriverOperation("submitDeletion({}, {})", target);
		formatDriver.submitDeletion(target);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		logDriverOperation("submitConditionalDeletion({}, {} = {})", target, precondition, requiredValue);
		formatDriver.submitConditionalDeletion(target, precondition, requiredValue);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		logDriverOperation("flush");
		formatDriver.flush();
	}

	@Override
	public void refurbish() throws IOException {
		logDriverOperation("refurbish");
		ClientSessionOptions sessionOptions = ClientSessionOptions.builder()
			.causallyConsistent(true)
			.defaultTransactionOptions(TransactionOptions.builder()
				.writeConcern(WriteConcern.MAJORITY)
				.readConcern(ReadConcern.MAJORITY)
				.build())
			.build();
		try (ClientSession session = mongoClient.startSession(sessionOptions)) {
			try {
				// Design note: this operation shouldn't do any special coordination with
				// the receiver/listener system, because other replicas won't.
				// That system needs to cope with a refurbish operations without any help.
				session.startTransaction();
				StateAndMetadata<R> result = formatDriver.loadAllState();
				FormatDriver<R> newFormatDriver = detectFormat();
				collection.deleteMany(new BsonDocument());
				newFormatDriver.initializeCollection(result);
				session.commitTransaction();
				formatDriver = newFormatDriver;
			} catch (UninitializedCollectionException e) {
				throw new IOException("Unable to refurbish uninitialized database collection", e);
			} finally {
				if (session.hasActiveTransaction()) {
					session.abortTransaction();
				}
			}
		}
	}

	@Override
	public void close() {
		logDriverOperation("close");
		isClosed = true;
		receiver.close();
		formatDriver.close();
	}

	/**
	 * Reinitializes {@link #receiver}, detects the database format, instantiates
	 * the appropriate {@link FormatDriver}, and uses it to load the initial bosk state.
	 * <p>
	 * Caller is responsible for calling {@link #receiver}{@link ChangeEventReceiver#start() .start()}
	 * to kick off event processing. We don't do it here because some callers need to do other things
	 * after initialization but before any events arrive.
	 *
	 * @return The new root object to use, if any
	 * @throws UninitializedCollectionException if the database or collection doesn't exist
	 */
	private R initializeReplication() throws UninitializedCollectionException {
		if (isClosed) {
			return null;
		}
		try {
			initializationLock.lock();
			formatDriver = new DisconnectedDriver<>(); // Fallback in case initialization fails
			if (receiver.initialize(new Listener())) {
				FormatDriver<R> newDriver = detectFormat();
				StateAndMetadata<R> result = newDriver.loadAllState();
				newDriver.onRevisionToSkip(result.revision);
				formatDriver = newDriver;
				return result.state;
			} else {
				LOGGER.warn("Unable to fetch resume token");
				return null;
			}
		} catch (ReceiverInitializationException | IOException e) {
			LOGGER.warn("Failed to initialize replication", e);
			return null;
		} finally {
			assert formatDriver != null;
			initializationLock.unlock();
		}
	}

	private final class Listener implements ChangeEventListener {
		/**
		 * Raise your hand if you want to think about the case where a listener keeps on processing
		 * events after an exception. Nobody? Ok, that's what I thought.
		 */
		volatile boolean isListening = true; // (volatile is probably overkill because all calls are on the same thread anyway)

		@Override
		public void onEvent(ChangeStreamDocument<Document> event) {
			if (isListening) {
				try {
					formatDriver.onEvent(event);
				} catch (RuntimeException e) {
					onException(e);
				}
			}
		}

		@Override
		public void onException(Exception e) {
			isListening = false;
			recoverFrom(e);
		}
	}

	private FormatDriver<R> detectFormat() throws UninitializedCollectionException, UnrecognizedFormatException {
		FindIterable<Document> result = collection.find(new BsonDocument("_id", SingleDocFormatDriver.DOCUMENT_ID));
		try (MongoCursor<Document> cursor = result.cursor()) {
			if (cursor.hasNext()) {
				return newSingleDocFormatDriver();
			} else {
				throw new UninitializedCollectionException("Document doesn't exist");
			}
		}
	}

	private SingleDocFormatDriver<R> newSingleDocFormatDriver() {
		return new SingleDocFormatDriver<>(
			bosk, collection, driverSettings, bsonPlugin, downstream);
	}

	private void logDriverOperation(String description, Object... args) {
		if (LOGGER.isDebugEnabled()) {
			String formatString = "+ [" + bosk.name() + "] " + description;
			LOGGER.debug(formatString, args);
		}
	}

	public static final String COLLECTION_NAME = "boskCollection";
	private static final Logger LOGGER = LoggerFactory.getLogger(MainDriver.class);
}
