package tukano.impl;

import com.azure.cosmos.CosmosContainer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import tukano.api.Short;
import tukano.api.*;
import tukano.impl.data.Following;
import tukano.impl.data.Likes;
import utils.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static tukano.api.Result.ErrorCode.*;
import static tukano.api.Result.*;

public class JavaShorts implements Shorts {

	private final String SHORT_PREFIX = "short:";
	private final String COUNTER_PREFIX = "counter:";
	private final String SHORT_LIKES_LIST_PREFIX = "shortLikes:";
	private final String USER_SHORTS_LIST_PREFIX = "userShorts:";
	private final String FEED_PREFIX = "feed:";
	private final String FOLLOWERS_PREFIX = "followers:";

	private static Logger Log = Logger.getLogger(JavaShorts.class.getName());
	
	private static Shorts instance;

	private static String LIKES_NAME = "likes";

	private static String FOLLOWING_NAME = "following";

	private static final boolean isPostgree = "true".equalsIgnoreCase(Props.get("USE_POSTGREE", ""));
	private static final boolean hasCache = "true".equalsIgnoreCase(Props.get("HAS_CACHE", ""));

	private static CosmosDBLayer cosmos;

	private static CosmosContainer shortsContainer;
	private static CosmosContainer likesContainer;
	private static CosmosContainer followingContainer;
	
	synchronized public static Shorts getInstance() {
		if( instance == null )
			instance = new JavaShorts();
		if(!isPostgree){
			cosmos = CosmosDBLayer.getInstance();
			shortsContainer = cosmos.getDB().getContainer(Shorts.NAME);
			likesContainer = cosmos.getDB().getContainer(LIKES_NAME);
			followingContainer = cosmos.getDB().getContainer(FOLLOWING_NAME);
		}

		return instance;
	}

	private JavaShorts() {

	}
	
	
	@Override
	public Result<Short> createShort(String userId, String password) {
		Log.info(() -> format("createShort : userId = %s, pwd = %s\n", userId, password));

		//TODO verificar se é este o url correto
		String url = Props.get("BLOB_URL", "");
		return errorOrResult( okUser(userId, password), user -> {

			var shortId = format("%s+%s", userId, UUID.randomUUID());
			//var blobUrl = format("%s/%s/%s", TukanoRestServer.serverURI, Blobs.NAME, shortId);
			var blobUrl = format("%s/%s/%s", url, Blobs.NAME, shortId);
			var shrt = new Short(shortId, userId, blobUrl);

			Result<Short> res;
			if(isPostgree){
				res = errorOrValue(DB.insertOne(shrt), s -> s.copyWithLikes_And_Token(0));
			}else{
				res =  errorOrValue(cosmos.insertOne(shrt, shortsContainer), s -> s.copyWithLikes_And_Token(0));
			}


			if(res.isOK()) {
				try (Jedis jedis = RedisCache.getCachePool().getResource()) {

					//Store short in cache
					var key = SHORT_PREFIX + res.value().getShortId();
					var value = JSON.encode(res.value());
					jedis.setex(key,120, value);

					var sKey = USER_SHORTS_LIST_PREFIX + userId;
					var list = jedis.lrange(sKey, 0, -1);

					if(!list.isEmpty()){
						//if the list of usershorts is not empty just add it
						//Log.info(() -> format("\n\nLISTA DE SHORTS DO USER NÃO ESTA VAZIA %s\n\n", shortId));
						jedis.lpush(sKey, JSON.encode(res.value().getShortId()));
						jedis.expire(sKey, 120);
					}else{
						//if the list of usershorts is empty there are two options, or it's the first user short
						// or the remaining shorts have expired from cache
						List<Map> userShorts;

						//get the userShorts from the DB
						if(isPostgree){
							var query = format("SELECT s.id FROM shorts s WHERE s.ownerId = '%s'", userId);
							userShorts =  DB.sql( query, Map.class);
						}else{
							var query = format("SELECT s.id FROM shorts s WHERE s.ownerId = '%s'", userId);
							userShorts =  cosmos.query(Map.class, query, shortsContainer).value();
						}

						List<String> ids = new ArrayList<>();

						//if it's the first short of the user just add it to the cache
						//else just get the list of the shorts (that already contains the new one) and store it in cache
						if (userShorts.isEmpty()) {
							ids.add(JSON.encode(res.value().getShortId()));
						}else{
							ids = userShorts.stream()
									.map(result -> result.get("id").toString())
									.toList();
						}

						jedis.rpush(sKey, ids.stream().map(JSON::encode).toArray(String[]::new));
						jedis.expire(sKey, 120);
					}
				}
			}

			return res;
		});
	}

	@Override
	public Result<Short> getShort(String shortId) {
		Log.info(() -> format("getShort : shortId = %s\n", shortId));

		if( shortId == null )
			return error(BAD_REQUEST);

		if(hasCache){
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {
				Long  likesCount;
				var lKey = COUNTER_PREFIX + shortId;
				var key = SHORT_PREFIX + shortId;

				//get as mget for more efficiency calling jedis
				List<String> values = jedis.mget(key, lKey);
				String value = values.get(0);
				String likes = values.get(1);

			/*
			Checking existence of likes in cache
			- If likes counter exist then get
			- else get from likes from DB and set value in cache
			*/
				if(likes != null) {
					likesCount = JSON.decode(likes, Long.class);
				}else {
					List<Likes> likesOnDB;
					if(isPostgree){
						var query = format("SELECT * FROM likes l WHERE l.shortId = '%s'", shortId);
						likesOnDB = DB.sql(query, Likes.class);
					}else{
						var query = format("SELECT * FROM Likes l WHERE l.shortId = '%s'", shortId);
						likesOnDB = cosmos.query(Likes.class, query, likesContainer).value();
					}

					likesCount = likesOnDB.isEmpty() ? 0 : (long) likesOnDB.size();
					jedis.setex(lKey,120, String.valueOf(likesCount));
				}

			/*
			Checking short existence in cache
			- if exists then extend expire time and return
			with the likes count retrieved earlier
			 */
				if(value != null) {
					Short shortToGetWithLikes = JSON.decode(value, Short.class).copyWithLikes_And_Token(likesCount);
					return ok(shortToGetWithLikes);
				}

				if(isPostgree){
					return errorOrValue( DB.getOne(shortId, Short.class), shrt ->{
						var val = JSON.encode(shrt);
						jedis.setex(key,120, val);
						return shrt.copyWithLikes_And_Token(likesCount);
					});
				}else{
					return errorOrResult( cosmos.getOne(shortId, Short.class, shortsContainer), shrt -> {
						var val = JSON.encode(shrt);
						jedis.setex(key,120, val);
						Short shortToGetWithLikes = shrt.copyWithLikes_And_Token(likesCount);
						return ok(shortToGetWithLikes);
					});
				}
			}
		}else{

			if(isPostgree){
				var query = format("SELECT count(*) FROM likes l WHERE l.shortId = '%s'", shortId);
				var likes = DB.sql(query, Long.class);
				return errorOrValue( DB.getOne(shortId, Short.class), shrt -> shrt.copyWithLikes_And_Token( likes.get(0)));
			}else{
				var query = format("SELECT * FROM Likes l WHERE l.shortId = '%s'", shortId);
				var likes = cosmos.query(Likes.class, query, likesContainer);
				long likesCount = likes.value().isEmpty() ? 0 : likes.value().size();

				return errorOrValue( cosmos.getOne(shortId, Short.class, shortsContainer), shrt -> shrt.copyWithLikes_And_Token(likesCount));
			}

		}
	}


	@Override
	public Result<Void> deleteShort(String shortId, String password) {
		Log.info(() -> format("deleteShort : shortId = %s, pwd = %s\n", shortId, password));

		if(isPostgree){
			return errorOrResult( getShort(shortId), shrt -> {
				return errorOrResult( okUser( shrt.getOwnerId(), password), user -> {
					return DB.transaction( hibernate -> {

						hibernate.remove( shrt);

						var query = format("DELETE FROM likes l WHERE l.shortId = '%s'", shortId);
						hibernate.createNativeQuery( query, Likes.class).executeUpdate();

						if(hasCache){
							try (Jedis jedis = RedisCache.getCachePool().getResource()) {
								//Pipeline pipeline = jedis.pipelined();

								var key = SHORT_PREFIX + shortId; //short
								jedis.del(key);

								var cKey = COUNTER_PREFIX + shortId; //counter likes
								jedis.del(cKey);

								var lKey = SHORT_LIKES_LIST_PREFIX + shortId; //list likes (user ids)
								jedis.del(lKey);

								var uKey = USER_SHORTS_LIST_PREFIX + user.getUserId();
								jedis.lrem(uKey, 0, shortId);

								//pipeline.sync();
								//Set<String> fKeys = jedis.keys(FEED_PREFIX);
								//fKeys.forEach(fKey -> jedis.lrem(fKey, 0, shortId));
							}
						}

						JavaBlobs.getInstance().delete(shrt.getShortId(), Token.get(shrt.getShortId()) );
					});
				});
			});
		}else{
			return errorOrResult( getShort(shortId), shrt -> {
				return errorOrResult( okUser( shrt.getOwnerId(), password), user -> {
					Result<?> res = cosmos.deleteOne( shrt, shortsContainer);
					if(!res.isOK())
						return Result.error(NOT_FOUND);

					//var query = format("DELETE Likes l WHERE l.shortId = '%s'", shortId);
					var query = format("SELECT * FROM Likes l WHERE l.shortId = '%s'", shortId);

					List<Likes> likes = cosmos.query( Likes.class, query, likesContainer).value();

					for(Likes l : likes){
						cosmos.deleteOne(l, likesContainer);
					}
					if(hasCache){
						try (Jedis jedis = RedisCache.getCachePool().getResource()) {
							//Pipeline pipeline = jedis.pipelined();

							var key = SHORT_PREFIX + shortId; //short
							jedis.del(key);

							var cKey = COUNTER_PREFIX + shortId; //counter likes
							jedis.del(cKey);

							var lKey = SHORT_LIKES_LIST_PREFIX + shortId; //list likes (user ids)
							jedis.del(lKey);

							var uKey = USER_SHORTS_LIST_PREFIX + user.getUserId();
							jedis.lrem(uKey, 0, shortId);

							//pipeline.sync();
							//Set<String> fKeys = jedis.keys(FEED_PREFIX);
							//fKeys.forEach(fKey -> jedis.lrem(fKey, 0, shortId));
						}
					}
					return JavaBlobs.getInstance().delete(shrt.getShortId(), Token.get(shrt.getShortId()));
				});
			});
		}
	}

	//TODO MOSTRAR AOS STORES PARA VER SE TA BOM
	@Override
	public Result<List<String>> getShorts(String userId) {
		Log.info(() -> format("getShorts : userId = %s\n", userId));

		if(hasCache){
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {
				var key = USER_SHORTS_LIST_PREFIX + userId;
				var value = jedis.lrange(key, 0, -1);

				if(!value.isEmpty()){
					//Log.info(() -> format("\n\nGET SHORTS: USER SHORTS EXISTEM NA CACHE %s\n\n", userId));
					List<String> res = value.stream().map(shrt -> JSON.decode(shrt, String.class)).toList();
					return ok(res);
				}

				//Log.info(() -> format("\n\nGET SHORTS: USER SHORTS NÃO EXISTEM NA CACHE %s\n\n", userId));
				List<String> ids;
				if(isPostgree){
					var query = format("SELECT s.id FROM shorts s WHERE s.ownerId = '%s'", userId);
					ids = errorOrValue( okUser(userId), DB.sql( query, String.class)).value();
				}else{
					var query = format("SELECT s.id FROM shorts s WHERE s.ownerId = '%s'", userId);
					List<Map> res = errorOrValue( okUser(userId), cosmos.query(Map.class, query, shortsContainer)).value();
					ids = res.stream().map(result -> result.get("id").toString()).toList();
				}

				if(!ids.isEmpty()){
					jedis.rpush(key, ids.stream().map(JSON::encode).toArray(String[]::new));
					jedis.expire(key,120);
				}

				return Result.ok(ids);
			}
		}else{
			if(isPostgree){
				var query = format("SELECT s.shortId FROM shorts s WHERE s.ownerId = '%s'", userId);
				return errorOrValue( okUser(userId), DB.sql( query, String.class));
			}else{
				var query = format("SELECT s.id FROM shorts s WHERE s.ownerId = '%s'", userId);
				List<Map> res = errorOrValue( okUser(userId), cosmos.query(Map.class, query, shortsContainer)).value();
				List<String> ids = res.stream().map(result -> result.get("id").toString()).toList();
				return Result.ok(ids);
			}
		}

	}

	@Override
	public Result<Void> follow(String userId1, String userId2, boolean isFollowing, String password) {
		Log.info(() -> format("follow : userId1 = %s, userId2 = %s, isFollowing = %s, pwd = %s\n", userId1, userId2, isFollowing, password));

		Result<Void> res = null;
		if(isPostgree){
			res = errorOrResult( okUser(userId1, password), user -> {
				var f = new Following(userId1, userId2);
				return errorOrVoid( okUser( userId2), isFollowing ? DB.insertOne( f ) : DB.deleteOne( f ));
			});
		}else{
			res = errorOrResult( okUser(userId1, password), user -> {
				var f = new Following(userId1, userId2);
				return errorOrVoid( okUser( userId2), isFollowing ? cosmos.insertOne( f , followingContainer) : cosmos.deleteOne( f , followingContainer));
			});
		}
		
		if(res.isOK() && hasCache){
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {

				var key = FOLLOWERS_PREFIX + userId2;
				List<String> followersList = jedis.lrange(key, 0, -1);
				if(!followersList.isEmpty()){
					Log.info(() -> format("\n\nFOLLOW: LISTA DE FOLLOWERS EXISTE %s\n\n", userId1));
					if (isFollowing) {
						jedis.lpush(key, JSON.encode(userId1));
					}else{
						jedis.lrem(key,0, JSON.encode(userId1));
					}
				}else {
					List<String> followers;
					if(isPostgree){
						Log.info(() -> format("\n\nFOLLOW: LISTA DE FOLLOWERS NÃO EXISTE %s\n\n", userId1));
						var query = format("SELECT f.follower FROM following f WHERE f.followee = '%s'", userId2);
						followers = errorOrValue( okUser(userId2, password), DB.sql(query, String.class)).value();
						Log.info(() -> format("\n\nFOLLOW: LISTA DE FOLLOWERS NÃO EXISTE %s\n\n", followers));
					}else{
						var query = format("SELECT f.follower FROM Following f WHERE f.followee = '%s'", userId2);
						List<Map> fList = errorOrValue( okUser(userId2), cosmos.query(Map.class, query, followingContainer)).value();
						followers = fList.stream().map(result -> result.get("follower").toString()).toList();
					}

					List<String> ids = new ArrayList<>();
					if (followers.isEmpty()) {
						ids.add(userId1);
					}else{
						ids = followers;
					}
					jedis.rpush(key, ids.stream().map(JSON::encode).toArray(String[]::new));
					jedis.expire(key,120);
				}
			}
		}

		return res;
	}

	@Override
	public Result<List<String>> followers(String userId, String password) {
		Log.info(() -> format("followers : userId = %s, pwd = %s\n", userId, password));

		if(hasCache) {
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {
				var key = FOLLOWERS_PREFIX + userId;
				List<String> followersList = jedis.lrange(key, 0, -1);
				if (!followersList.isEmpty()) {
					return ok(followersList.stream()
							.map(f -> JSON.decode(f, String.class))
							.toList());
				}
				//Log.info(() -> format("\n\nFOLLOWERS: LISTA DE FOLLOWERS NÃO EXISTE NA CACHE %s\n\n", userId));
				List<String> ids;
				if(isPostgree){
					var query = format("SELECT f.follower FROM following f WHERE f.followee = '%s'", userId);
					 ids = errorOrValue( okUser(userId, password), DB.sql(query, String.class)).value();
				}else{
					var query = format("SELECT f.follower FROM Following f WHERE f.followee = '%s'", userId);
					Result<List<Map>> res = errorOrValue(okUser(userId), cosmos.query(Map.class, query, followingContainer));
					ids = res.value().stream().map(result -> result.get("follower").toString()).toList();
				}

				if(!ids.isEmpty()){
					jedis.rpush(key, ids.stream().map(JSON::encode).toArray(String[]::new));
					jedis.expire(key, 120);
				}
				return ok(ids);
			}
		}else {
			if(isPostgree){
				var query = format("SELECT f.follower FROM following f WHERE f.followee = '%s'", userId);
				return errorOrValue( okUser(userId, password), DB.sql(query, String.class));
			}else{
				var query = format("SELECT f.follower FROM Following f WHERE f.followee = '%s'", userId);
				Result<List<Map>> res = errorOrValue( okUser(userId), cosmos.query(Map.class, query, followingContainer));
				List<String> ids = res.value().stream().map(result -> result.get("follower").toString()).toList();
				return Result.ok(ids);
			}


		}
	}

	@Override
	public Result<Void> like(String shortId, String userId, boolean isLiked, String password) {
		Log.info(() -> format("like : shortId = %s, userId = %s, isLiked = %s, pwd = %s\n", shortId, userId, isLiked, password));

		Result<Void> res;
		if(isPostgree){
			res =  errorOrResult( getShort(shortId), shrt -> {
				var l = new Likes(userId, shortId, shrt.getOwnerId());
				return errorOrVoid( okUser( userId, password), isLiked ? DB.insertOne( l ) : DB.deleteOne( l ));
			});
		}else{
			res = errorOrResult( getShort(shortId), shrt -> {
				var l = new Likes(userId, shortId, shrt.getOwnerId());
				return errorOrVoid( okUser( userId, password), isLiked ? cosmos.insertOne( l, likesContainer) : cosmos.deleteOne( l, likesContainer));
			});
		}

		if(res.isOK() && hasCache){
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {

				var key = SHORT_LIKES_LIST_PREFIX + shortId;
				List<String> likesList = jedis.lrange(key, 0, -1);
				var lKey = COUNTER_PREFIX + shortId;
				var value = jedis.get(lKey);

				if(!likesList.isEmpty()) {
					//Log.info(() -> format("\n\nLIKE: LISTA DE LIKES EXISTE NA CACHE %s\n\n", shortId));
					if (isLiked) {
						jedis.lpush(key, JSON.encode(userId));
					}else{
						jedis.lrem(key,1, JSON.encode(userId));
					}
				}else{
					List<String> like = getLikes(shortId,isPostgree);

					List<String> ids = new ArrayList<>();
					if (like.isEmpty()) {
						ids.add(userId);
					}else{
						ids = like;
					}
					jedis.rpush(key, ids.stream().map(JSON::encode).toArray(String[]::new));
					jedis.expire(key,120);

					if(value == null){
						long likesCount = like.isEmpty() ? 0 : like.size();
						jedis.setex(lKey,120, String.valueOf(likesCount));
					}
				}

				if(value != null){
					if(isLiked){
						jedis.incr(lKey);
					}else{
						jedis.decr(lKey);
					}
					//Log.info(() -> format("\n\nLIKE: COUNTER DE LIKES EXISTE NA CACHE %d\n\n", newVal));
				}else {

					List<String> like = getLikes(shortId, isPostgree);

					long likesCount = like.isEmpty() ? 0 : like.size();
					jedis.setex(lKey,120, String.valueOf(likesCount));
				}

			}
		}

		return res;
	}

	private List<String> getLikes(String shortId, boolean isPostgree){
		if(isPostgree){
			var query = format("SELECT l.userId FROM likes l WHERE l.shortId = '%s'", shortId);
			return DB.sql(query, String.class);
		}else{
			var query = format("SELECT * FROM Likes l WHERE l.shortId = '%s'", shortId);
			List<Map> likes = cosmos.query(Map.class, query, likesContainer).value();
			return likes.stream().map(result -> result.get("userId").toString()).toList();
		}
	}

	//TODO VER ISTO COM O STOR
	@Override
	public Result<List<String>> likes(String shortId, String password) {
		Log.info(() -> format("likes : shortId = %s, pwd = %s\n", shortId, password));

		if(hasCache) {
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {

				var key = SHORT_LIKES_LIST_PREFIX + shortId;
				var likesList = jedis.lrange(key, 0, -1);
				if (!likesList.isEmpty()) {
					//Log.info(() -> format("\n\nLIKES: LISTA DE LIKES EXISTE NA CACHE %s\n\n", shortId));
					return ok(likesList.stream()
									.map(f -> JSON.decode(f, String.class))
									.collect(Collectors.toList())
					);
				}
				//Log.info(() -> format("\n\nLIKES: LISTA DE LIKES NÃO EXISTE NA CACHE %s\n\n", shortId));
				return errorOrResult(getShort(shortId), shrt -> {

					List<String> ids = getLikes(shortId, isPostgree);

					if(!ids.isEmpty()){
						jedis.rpush(key, ids.stream().map(JSON::encode).toArray(String[]::new));
						jedis.expire(key,120);
					}
					return Result.ok(ids);
				});
			}
		}else{
			if(isPostgree){
				return errorOrResult( getShort(shortId), shrt -> {

					var query = format("SELECT l.userId FROM Likes l WHERE l.shortId = '%s'", shortId);

					return errorOrValue( okUser( shrt.getOwnerId(), password), DB.sql(query, String.class));
				});
			}else {
				return errorOrResult(getShort(shortId), shrt -> {
					var query = format("SELECT * FROM Likes l WHERE l.shortId = '%s'", shortId);
					Result<List<Map>> res = cosmos.query(Map.class, query, likesContainer);
					if(!res.isOK())
						return error(res.error());
					List<String> ids = res.value().stream().map(result -> result.get("userId").toString()).toList();
					return Result.ok(ids);
				});
			}
		}
	}

	@Override
	public Result<List<String>> getFeed(String userId, String password) {
		Log.info(() -> format("getFeed : userId = %s, pwd = %s\n", userId, password));

		if(isPostgree){
			final var QUERY_FMT = """
				SELECT s.id, s.timestamp 
				FROM shorts s 
				WHERE s.ownerId = '%s'                
				UNION            
				SELECT s2.id, s2.timestamp 
				FROM shorts s2 
				JOIN Following f ON f.followee = s2.ownerId 
				WHERE f.follower = '%s' 
				ORDER BY timestamp DESC""";

			return errorOrValue( okUser( userId, password), DB.sql( format(QUERY_FMT, userId, userId), String.class));
		}else{
			final var FIRST_QUERY = format("SELECT s.id, s.timestamp FROM shorts s WHERE s.ownerId = '%s'", userId);
			List<Map> queryRes1 = cosmos.query(Map.class, FIRST_QUERY, shortsContainer).value();

			List<Tuple<String, Long>> res1 = queryRes1.stream()
					.map(result -> new Tuple<>((String) result.get("id"), (Long) result.get("timestamp")))
					.collect(Collectors.toList());


			final var SECOND_QUERY = format("SELECT f.followee FROM Following f WHERE f.follower = '%s'", userId);
			List<Map> res2 = cosmos.query(Map.class, SECOND_QUERY, followingContainer).value();
			List<String> followees = res2.stream().map(result -> result.get("followee").toString()).toList();

			List<Tuple<String, Long>> resultTuples = new ArrayList<>();

			for (String f : followees) {
				String query = String.format("SELECT s.id, s.timestamp FROM shorts s WHERE s.ownerId = '%s'", f);
				List<Map> queryResult = cosmos.query(Map.class, query, shortsContainer).value();

				List<Tuple<String, Long>> tuples = queryResult.stream()
						.map(result -> new Tuple<>((String) result.get("id"), (Long) result.get("timestamp")))
						.collect(Collectors.toList());

				resultTuples.addAll(tuples);
			}

			res1.addAll(resultTuples);

			res1.sort((t1, t2) -> Long.compare(t2.getT2(), t1.getT2()));

			List<String> result = new ArrayList<>();
			for (Tuple<String, Long> s : res1){
				result.add(s.getT1());
			}

//		List<String> ids = res.stream().map(result -> result.get("id").toString()).toList();
			return Result.ok(result);
//		return errorOrValue( okUser( userId, password), cosmos.query(String.class, format(QUERY_FMT, userId, userId), shortsContainer));
		}
	}
		
	protected Result<User> okUser( String userId, String pwd) {
		return JavaUsers.getInstance().getUser(userId, pwd);
	}
	
	private Result<Void> okUser( String userId) {
		var res = okUser( userId, "");
		//Log.info(()->String.format("\n\nERROR OK USER: %s\n\n", res.error().toString()));
		if( res.error() == FORBIDDEN )
			return ok();
		else
			return error( res.error() );
	}
	
	@Override
	public Result<Void> deleteAllShorts(String userId, String password, String token) {
		Log.info(() -> format("deleteAllShorts : userId = %s, password = %s, token = %s\n", userId, password, token));

		if( ! Token.isValid( token, userId ) )
			return error(FORBIDDEN);
		try {
			if(hasCache) {
				try (Jedis jedis = RedisCache.getCachePool().getResource()) {
					var sKey = USER_SHORTS_LIST_PREFIX + userId;
					jedis.del(sKey);
					var fKey = FOLLOWERS_PREFIX + userId;
					jedis.del(fKey);
				}
			}

			if(isPostgree){
				if(hasCache){
					try (Jedis jedis = RedisCache.getCachePool().getResource()) {
						Pipeline pipe = jedis.pipelined();

						var query1Select = format("SELECT s.id FROM shorts s WHERE s.ownerId = '%s'", userId);
						List<String> res1 = DB.sql(query1Select, String.class);
						for (String s : res1) {
							var key = SHORT_LIKES_LIST_PREFIX + s;
							pipe.del(key);
							var sKey = SHORT_PREFIX + s;
							pipe.del(sKey);
							var cKey = COUNTER_PREFIX + s;
							pipe.del(cKey);
						}

						var query2Select = format("SELECT f.followee FROM following f WHERE f.follower = '%s'", userId);
						List<String> res2 = DB.sql(query2Select, String.class);
						for (String f : res2) {
							var key = USER_SHORTS_LIST_PREFIX + f;
							pipe.lrem(key, 0, JSON.encode(userId));
						}

						var query3Select = format("SELECT l.shortId FROM likes l WHERE l.userId = '%s'", userId);
						List<String> res3 = DB.sql(query3Select, String.class);
						for (String l : res3) {
							var key = USER_SHORTS_LIST_PREFIX + l;
							pipe.lrem(key, 0, JSON.encode(userId));
						}
						pipe.sync();
					}
				}

				return DB.transaction( (hibernate) -> {
					//delete shorts
					var query1 = format("DELETE shorts s WHERE s.ownerId = '%s'", userId);
					hibernate.createQuery(query1, Short.class).executeUpdate();

					//delete follows
					var query2 = format("DELETE following f WHERE f.follower = '%s' OR f.followee = '%s'", userId, userId);
					hibernate.createQuery(query2, Following.class).executeUpdate();

					//delete likes
					var query3 = format("DELETE likes l WHERE l.ownerId = '%s' OR l.userId = '%s'", userId, userId);
					hibernate.createQuery(query3, Likes.class).executeUpdate();
				});
			}else{
				//delete shorts
				//var query1 = format("DELETE Short s WHERE s.ownerId = '%s'", userId);
				var query1 = format("SELECT * FROM shorts s WHERE s.ownerId = '%s'", userId);
				List<Short> shorts = cosmos.query(Short.class, query1, shortsContainer).value();
				for(Short s : shorts){
					cosmos.deleteOne(s, shortsContainer);
				}

				//delete follows
				//var query2 = format("DELETE Following f WHERE f.follower = '%s' OR f.followee = '%s'", userId, userId);
				var query2 = format("SELECT * FROM Following f WHERE f.follower = '%s' OR f.followee = '%s'",userId, userId);
				List<Following> following = cosmos.query(Following.class, query2, followingContainer).value();
				for(Following f : following){
					cosmos.deleteOne(f, followingContainer);
				}

				//delete likes
				//var query3 = format("DELETE Likes l WHERE l.ownerId = '%s' OR l.userId = '%s'", userId, userId);
				var query3 = format("SELECT * FROM Likes l WHERE l.ownerId = '%s' OR l.userId = '%s'", userId, userId);
				List<Likes> likes = cosmos.query(Likes.class, query3, likesContainer).value();
				for(Likes l: likes){
					cosmos.deleteOne(l,likesContainer );
				}

				if(hasCache){
					try (Jedis jedis = RedisCache.getCachePool().getResource()) {
						Pipeline pipe = jedis.pipelined();
						for (Short s : shorts) {
							var key = SHORT_LIKES_LIST_PREFIX + s.getShortId();
							pipe.del(key);
							var sKey = SHORT_PREFIX + s.getShortId();
							pipe.del(sKey);
							var cKey = COUNTER_PREFIX + s.getShortId();
							pipe.del(cKey);
						}

						for (Likes l : likes) {
							var key = USER_SHORTS_LIST_PREFIX + l.getShortId();
							pipe.lrem(key, 0, JSON.encode(l.getUserId()));
						}

						for (Following f : following) {
							var key = USER_SHORTS_LIST_PREFIX + f.getFollowee();
							pipe.lrem(key, 0, JSON.encode(f.getFollower()));
						}
						pipe.sync();
					}
				}
			}

		} catch (Exception e) {
			return Result.error(INTERNAL_ERROR);
		}
		return ok();
	}
	
}