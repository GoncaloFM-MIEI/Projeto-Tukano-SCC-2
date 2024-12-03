package tukano.impl;

import static java.lang.String.format;
import static tukano.api.Result.ErrorCode.*;
import static tukano.api.Result.error;
import static tukano.api.Result.errorOrResult;
import static tukano.api.Result.errorOrValue;
import static tukano.api.Result.errorOrVoid;
import static tukano.api.Result.ok;
import static utils.DB.getOne;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import redis.clients.jedis.Jedis;
import tukano.api.Blobs;
import tukano.api.Result;
import tukano.api.Short;
import tukano.api.Shorts;
import tukano.api.User;
import tukano.impl.data.Following;
import tukano.impl.data.Likes;
import tukano.impl.rest.TukanoRestServer;
import utils.DB;
import utils.JSON;
import utils.RedisCache;

public class JavaShorts implements Shorts {

	private final String SHORT_PREFIX = "short:";
	private final String COUNTER_PREFIX = "counter:";
	private final String SHORT_LIKES_LIST_PREFIX = "shortLikes:";
	private final String USER_SHORTS_LIST_PREFIX = "userShorts:";
	private final String FEED_PREFIX = "feed:";
	private final String FOLLOWERS_PREFIX = "followers:";

	private static Logger Log = Logger.getLogger(JavaShorts.class.getName());
	
	private static Shorts instance;
	
	synchronized public static Shorts getInstance() {
		if( instance == null )
			instance = new JavaShorts();
		return instance;
	}
	
	private JavaShorts() {}
	
	
	@Override
	public Result<Short> createShort(String userId, String password) {
		Log.info(() -> format("createShort : userId = %s, pwd = %s\n", userId, password));

		return errorOrResult( okUser(userId, password), user -> {
			
			var shortId = format("%s+%s", userId, UUID.randomUUID());
			var blobUrl = format("%s/%s/%s", TukanoRestServer.serverURI, Blobs.NAME, shortId); 
			var shrt = new Short(shortId, userId, blobUrl);

			Result<Short> res = errorOrValue(DB.insertOne(shrt), s -> s.copyWithLikes_And_Token(0));

			if(res.isOK()) {
				try (Jedis jedis = RedisCache.getCachePool().getResource()) {

					var key = SHORT_PREFIX + res.value().getShortId();
					var value = JSON.encode(res.value());
					jedis.setex(key,120, value);

					var sKey = USER_SHORTS_LIST_PREFIX + userId;
					var list = jedis.lrange(sKey, 0, -1);

					if(!list.isEmpty()){
						jedis.lpush(sKey, JSON.encode(res.value().getShortId()));
						jedis.expire(sKey, 120);
					}else{
						List<Map> userShorts;

						var query = format("SELECT s.shortId FROM Short s WHERE s.ownerId = '%s'", userId);
						userShorts =  DB.sql( query, Map.class);

						List<String> ids = new ArrayList<>();

						Log.info(() -> format("SHORTS DO USER%s\n", userShorts.toString()));

						//if (userShorts.isEmpty() || userShorts == null) {
						//	ids.add(JSON.encode(res.value().getShortId()));
						//}else{
							ids = userShorts.stream()
									.map(result -> result.get("shortid").toString())
									.toList();
						//}

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

				var query = format("SELECT * FROM Likes l WHERE l.shortId = '%s'", shortId);
				likesOnDB = DB.sql(query, Likes.class);

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

			return errorOrValue( DB.getOne(shortId, Short.class), shrt ->{
				var val = JSON.encode(shrt);
				jedis.setex(key,120, val);
				return shrt.copyWithLikes_And_Token(likesCount);
			});

		}
	}

	
	@Override
	public Result<Void> deleteShort(String shortId, String password) {
		Log.info(() -> format("deleteShort : shortId = %s, pwd = %s\n", shortId, password));

		return errorOrResult(getShort(shortId), shrt -> {
			return errorOrResult(okUser(shrt.getOwnerId(), password), user -> {
				Result<Void> dbResult = DB.transaction(hibernate -> {
					hibernate.remove(shrt);

					var query = format("DELETE FROM Likes l WHERE l.shortId = '%s'", shortId);
					hibernate.createNativeQuery(query, Likes.class).executeUpdate();
				});

				if (!dbResult.isOK()) {
					return dbResult;
				}

				try (Jedis jedis = RedisCache.getCachePool().getResource()) {
					var key = SHORT_PREFIX + shortId;
					jedis.del(key);

					var cKey = COUNTER_PREFIX + shortId;
					jedis.del(cKey);

					var lKey = SHORT_LIKES_LIST_PREFIX + shortId;
					jedis.del(lKey);

					var uKey = USER_SHORTS_LIST_PREFIX + user.getUserId();
					jedis.lrem(uKey, 0, JSON.encode(shortId));
				}

				JavaBlobs.getInstance().delete(shrt.getShortId(), Token.get(shrt.getShortId()));
				return Result.ok();
			});
		});
	}

	@Override
	public Result<List<String>> getShorts(String userId) {
		Log.info(() -> format("getShorts : userId = %s\n", userId));

		try (Jedis jedis = RedisCache.getCachePool().getResource()) {
			var key = USER_SHORTS_LIST_PREFIX + userId;
			var value = jedis.lrange(key, 0, -1);

			if(!value.isEmpty()){
				//Log.info(() -> format("\n\nGET SHORTS: USER SHORTS EXISTEM NA CACHE %s\n\n", userId));
				List<String> res = value.stream().map(shrt -> JSON.decode(shrt, String.class)).toList();
				return ok(res);
			}

			List<String> ids;
			var query = format("SELECT s.shortId FROM Short s WHERE s.ownerId = '%s'", userId);
			ids = errorOrValue( okUser(userId), DB.sql( query, String.class)).value();


			if(!ids.isEmpty()){
				jedis.rpush(key, ids.stream().map(JSON::encode).toArray(String[]::new));
				jedis.expire(key,120);
			}

			return Result.ok(ids);
		}
	}

	@Override
	public Result<Void> follow(String userId1, String userId2, boolean isFollowing, String password) {
		Log.info(() -> format("follow : userId1 = %s, userId2 = %s, isFollowing = %s, pwd = %s\n", userId1, userId2, isFollowing, password));


		Result<Void> res = errorOrResult( okUser(userId1, password), user -> {
					var f = new Following(userId1, userId2);
					return errorOrVoid( okUser( userId2), isFollowing ? DB.insertOne( f ) : DB.deleteOne( f ));
					});


		if(res.isOK()){
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


					var query = format("SELECT f.follower FROM Following f WHERE f.followee = '%s'", userId2);
					followers = errorOrValue( okUser(userId2, password), DB.sql(query, String.class)).value();

					Log.info(() -> format("\n\nFOLLOW: LISTA DE FOLLOWERS NÃO EXISTE %s\n\n", followers));

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

		try (Jedis jedis = RedisCache.getCachePool().getResource()) {
			var key = FOLLOWERS_PREFIX + userId;
			List<String> followersList = jedis.lrange(key, 0, -1);
			if (!followersList.isEmpty()) {
				return ok(followersList.stream()
						.map(f -> JSON.decode(f, String.class))
						.toList());
			}
			List<String> ids;

			var query = format("SELECT f.follower FROM Following f WHERE f.followee = '%s'", userId);
			ids = errorOrValue( okUser(userId, password), DB.sql(query, String.class)).value();

			if(!ids.isEmpty()){
				jedis.rpush(key, ids.stream().map(JSON::encode).toArray(String[]::new));
				jedis.expire(key, 120);
			}
			return ok(ids);
		}
	}

	@Override
	public Result<Void> like(String shortId, String userId, boolean isLiked, String password) {
		Log.info(() -> format("like : shortId = %s, userId = %s, isLiked = %s, pwd = %s\n", shortId, userId, isLiked, password));


		Result<Void> res =  errorOrResult( getShort(shortId), shrt -> {
				var l = new Likes(userId, shortId, shrt.getOwnerId());
				return errorOrVoid( okUser( userId, password), isLiked ? DB.insertOne( l ) : DB.deleteOne( l ));
				});

		if(res.isOK() ){
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {

				var key = SHORT_LIKES_LIST_PREFIX + shortId;
				List<String> likesList = jedis.lrange(key, 0, -1);
				var lKey = COUNTER_PREFIX + shortId;
				var value = jedis.get(lKey);

				if(!likesList.isEmpty()) {
					Log.info(() -> format("\n\nLIKE: LISTA DE LIKES EXISTE NA CACHE %s\n\n", shortId));
					if (isLiked) {
						jedis.lpush(key, JSON.encode(userId));
					}else{
						jedis.lrem(key,1, JSON.encode(userId));
					}
				}else{
					List<String> like = getLikes(shortId);

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
					Log.info(() -> format("\n\nLIKE: COUNTER DE LIKES EXISTE NA CACHE %d\n\n"));
				}else {

					List<String> like = getLikes(shortId);

					long likesCount = like.isEmpty() ? 0 : like.size();
					jedis.setex(lKey,120, String.valueOf(likesCount));
				}

			}
		}

		return res;
	}

	private List<String> getLikes(String shortId){
		var query = format("SELECT l.userId FROM Likes l WHERE l.shortId = '%s'", shortId);
		return DB.sql(query, String.class);
	}

	@Override
	public Result<List<String>> likes(String shortId, String password) {
		Log.info(() -> format("likes : shortId = %s, pwd = %s\n", shortId, password));

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

				List<String> ids = getLikes(shortId);

				if(!ids.isEmpty()){
					jedis.rpush(key, ids.stream().map(JSON::encode).toArray(String[]::new));
					jedis.expire(key,120);
				}
				return Result.ok(ids);
			});
		}
	}

	@Override
	public Result<List<String>> getFeed(String userId, String password) {
		Log.info(() -> format("getFeed : userId = %s, pwd = %s\n", userId, password));

		final var QUERY_FMT = """
				SELECT s.shortId, s.timestamp FROM Short s WHERE s.ownerId = '%s'				
				UNION			
				SELECT s.shortId, s.timestamp FROM Short s, Following f 
					WHERE 
						f.followee = s.ownerId AND f.follower = '%s' 
				ORDER BY s.timestamp DESC""";

		return errorOrValue( okUser( userId, password), DB.sql( format(QUERY_FMT, userId, userId), String.class));		
	}
		
	protected Result<User> okUser( String userId, String pwd) {
		return JavaUsers.getInstance().getUser(userId, pwd);
	}
	
	private Result<Void> okUser( String userId ) {
		var res = okUser( userId, "");
		if( res.error() == FORBIDDEN )
			return ok();
		else
			return error( res.error() );
	}

	@Override
	public Result<Void> deleteAllShorts(String userId, String password, String token) {
		Log.info(() -> format("deleteAllShorts : userId = %s, password = %s, token = %s\n", userId, password, token));

		if (!Token.isValid(token, userId))
			return error(FORBIDDEN);

		removeFromCache(userId, password);

		/*
		return DB.transaction((hibernate) -> {

			//delete shorts
			var query1 = format("DELETE FROM Short s WHERE s.ownerId = '%s'", userId);
			hibernate.createQuery(query1, Short.class).executeUpdate();

			//delete follows
			var query2 = format("DELETE FROM Following f WHERE f.follower = '%s' OR f.followee = '%s'", userId, userId);
			hibernate.createQuery(query2, Following.class).executeUpdate();

			//delete likes
			var query3 = format("DELETE FROM Likes l WHERE l.ownerId = '%s' OR l.userId = '%s'", userId, userId);
			hibernate.createQuery(query3, Likes.class).executeUpdate();
		});
		*/

		try {
			var query1 = format("DELETE FROM Short s WHERE s.ownerId = '%s'", userId);
			DB.sql(query1, Short.class);

			var query2 = format("DELETE FROM Following f WHERE f.follower = '%s' OR f.followee = '%s'", userId, userId);
			DB.sql(query2, Following.class);

			var query3 = format("DELETE FROM Likes l WHERE l.ownerId = '%s' OR l.userId = '%s'", userId, userId);
			DB.sql(query3, Likes.class);

			return ok();
		} catch (Exception e) {
			return error(INTERNAL_ERROR);
		}
	}


	public void removeFromCache(String userId, String password) {

		try (Jedis jedis = RedisCache.getCachePool().getResource()) {
			var sKey = USER_SHORTS_LIST_PREFIX + userId;
			jedis.del(sKey);
			var fKey = FOLLOWERS_PREFIX + userId;
			jedis.del(fKey);
		}

		try (Jedis jedis = RedisCache.getCachePool().getResource()) {

			var query1Select = format("SELECT s.shortid FROM Short s WHERE s.ownerId = '%s'", userId);
			List<String> res1 = DB.sql(query1Select, String.class);
			for (String s : res1) {
				var key = SHORT_LIKES_LIST_PREFIX + s;
				jedis.del(key);
				var sKey = SHORT_PREFIX + s;
				jedis.del(sKey);
				var cKey = COUNTER_PREFIX + s;
				jedis.del(cKey);
			}

			var query2Select = format("SELECT f.followee FROM Following f WHERE f.follower = '%s'", userId);
			List<String> res2 = DB.sql(query2Select, String.class);
			for (String f : res2) {
				var key = USER_SHORTS_LIST_PREFIX + f;
				jedis.lrem(key, 0, JSON.encode(userId));
			}

			var query3Select = format("SELECT l.shortid FROM Likes l WHERE l.userId = '%s'", userId);
			List<String> res3 = DB.sql(query3Select, String.class);
			for (String l : res3) {
				var key = USER_SHORTS_LIST_PREFIX + l;
				jedis.lrem(key, 0, JSON.encode(userId));
			}

		}

	}













	
}