package utils;

import com.azure.cosmos.*;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import tukano.api.Result;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;


public class CosmosDBLayer {
	private static Logger Log = Logger.getLogger(CosmosDBLayer.class.getName());

	private static final String CONNECTION_URL = Props.get("COSMOSDB_URL", "");; // replace with your own
	private static final String DB_KEY = Props.get("COSMOSDB_KEY", "");
	private static final String DB_NAME = Props.get("COSMOSDB_DATABASE", "");
	private static String containerName;

	private static CosmosDBLayer instance;

	public static synchronized CosmosDBLayer getInstance() {
		//if( instance != null && containerName.equals(container))
		//	return instance;

		//containerName = container;
		CosmosClient client = new CosmosClientBuilder()
		         .endpoint(CONNECTION_URL)
		         .key(DB_KEY)
		         //.directMode()
		         .gatewayMode()
		         // replace by .directMode() for better performance
		         .consistencyLevel(ConsistencyLevel.SESSION)
		         .connectionSharingAcrossClientsEnabled(true)
		         .contentResponseOnWriteEnabled(true)
		         .buildClient();
		instance = new CosmosDBLayer( client);
		return instance;

	}

	private CosmosClient client;
	private CosmosDatabase db;
	//private CosmosContainer container;

	public CosmosDBLayer(CosmosClient client) {
		this.client = client;
	}

	private synchronized void init() {
		if( db != null)
			return;
		db = client.getDatabase(DB_NAME);
		//container = db.getContainer(containerName);
	}

	public void close() {
		client.close();
	}

	public CosmosDatabase getDB() {
		return tryCatch( () -> this.db).value();
	}

	public <T> Result<T> getOne(String id, Class<T> clazz, CosmosContainer container) {
		return tryCatch( () -> container.readItem(id, new PartitionKey(id), clazz).getItem());
	}

	public <T> Result<?> deleteOne(T obj, CosmosContainer container) {
		return tryCatch( () -> container.deleteItem(obj, new CosmosItemRequestOptions()).getItem());
	}

	public <T> Result<T> updateOne(T obj, CosmosContainer container) {
		return tryCatch( () -> container.upsertItem(obj).getItem());
	}

	public <T> Result<T> insertOne( T obj, CosmosContainer container) {
		return tryCatch( () -> container.createItem(obj).getItem());
	}

	public <T> Result<List<T>> query(Class<T> clazz, String queryStr, CosmosContainer container) {
		return tryCatch(() -> {
			var res = container.queryItems(queryStr, new CosmosQueryRequestOptions(), clazz);
			return res.stream().toList();
		});
	}

	<T> Result<T> tryCatch( Supplier<T> supplierFunc) {
		try {
			init();
			return Result.ok(supplierFunc.get());
		} catch( CosmosException ce ) {
			ce.printStackTrace();
			return Result.error ( errorCodeFromStatus(ce.getStatusCode() ));
		} catch( Exception x ) {
			x.printStackTrace();
			return Result.error( Result.ErrorCode.INTERNAL_ERROR);
		}
	}

	static Result.ErrorCode errorCodeFromStatus( int status ) {
		return switch( status ) {
		case 200 -> Result.ErrorCode.OK;
		case 404 -> Result.ErrorCode.NOT_FOUND;
		case 409 -> Result.ErrorCode.CONFLICT;
		default -> Result.ErrorCode.INTERNAL_ERROR;
		};
	}
}
