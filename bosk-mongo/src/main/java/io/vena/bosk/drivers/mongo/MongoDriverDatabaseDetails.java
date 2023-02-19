package io.vena.bosk.drivers.mongo;

/**
 * Provides details about the database to support testing
 * the effects of direct database changes.
 */
interface MongoDriverDatabaseDetails {
	String mainCollectionName();
	String rootDocumentID();
}
