package tukano.impl;

import static java.lang.String.format;
import static tukano.api.Result.error;
import static tukano.api.Result.errorOrResult;
import static tukano.api.Result.errorOrValue;
import static tukano.api.Result.ok;
import static tukano.api.Result.ErrorCode.BAD_REQUEST;
import static tukano.api.Result.ErrorCode.FORBIDDEN;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import redis.clients.jedis.Jedis;
import tukano.api.Result;
import tukano.api.User;
import tukano.api.Users;
import utils.DB;
import utils.JSON;
import utils.RedisCache;

public class JavaUsers implements Users {
	
	private static Logger Log = Logger.getLogger(JavaUsers.class.getName());

	private static Users instance;

	private static String USER_PREFIX = "user:";
	private static int EXPIRATION_TIME = 120;

	private static final String REDIS_HOST = System.getenv("REDIS_HOST"); // Kubernetes Service name
	private static final int REDIS_PORT = Integer.parseInt(System.getenv("REDIS_PORT"));

	//private Jedis jedis;
	
	synchronized public static Users getInstance() {
		if( instance == null )
			instance = new JavaUsers();
		return instance;
	}
	
	private JavaUsers() {
		//this.jedis = new Jedis(REDIS_HOST, REDIS_PORT);
	}
	
	@Override
	public Result<String> createUser(User user) {
		Log.info(() -> format("createUser : %s\n", user));

		if( badUserInfo( user ) )
				return error(BAD_REQUEST);


		Result<String> res = errorOrValue( DB.insertOne( user), user.getUserId());


		if (res.isOK()){
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {
				Log.info(()->String.format("\n\nCREATE USER (IN CACHE): %s\n\n", user.getUserId()));
				var key = USER_PREFIX + user.getUserId();
				var value = JSON.encode(user);
				jedis.setex(key, EXPIRATION_TIME, value);
			}
		}



		return res;
	}

	@Override
	public Result<User> getUser(String userId, String pwd) {
		Log.info( () -> format("getUser : userId = %s, pwd = %s\n", userId, pwd));

		if (userId == null)
			return error(BAD_REQUEST);

		if(pwd.isEmpty()){
			return error(FORBIDDEN);
		}

		try (Jedis jedis = RedisCache.getCachePool().getResource()) {

			var key = USER_PREFIX + userId;

			var value = jedis.get(key);


			if (value != null) {
				Log.info(() -> String.format("\nCache Access Time %s\n", JSON.decode(value, User.class))); // Convertendo para milissegundos
				return Result.ok(JSON.decode(value, User.class));
			}

			Result<User> u = validatedUserOrError( DB.getOne( userId, User.class), pwd);

			if (u.isOK()) {
				Log.info(() -> String.format("\n\nPUTTING USER IN CACHE: %s\n\n", u.value().getUserId()));
				var user = JSON.encode(u.value());
				jedis.setex(key,EXPIRATION_TIME, user);

			}

			return u;
		}
	}

	@Override
	public Result<User> updateUser(String userId, String pwd, User other) {
		Log.info(() -> format("updateUser : userId = %s, pwd = %s, user: %s\n", userId, pwd, other));

		if (badUpdateUserInfo(userId, pwd, other))
			return error(BAD_REQUEST);

		try (Jedis jedis = RedisCache.getCachePool().getResource()) {

			var key = USER_PREFIX + userId;
			var value = jedis.get(key);

			//if user exists in cache just get him to avoid getting him from the DB
			if (value != null) {
				var user = JSON.decode(value, User.class);
				Log.info(() -> String.format("\n\nUPDATE USER (IN CACHE) (OLD USER): %s\n\n", user.getUserId()));
				Result<User> res;

				res = errorOrValue(DB.updateOne( user.updateFrom(other)), other);

				//after updating, just overrite the same key with the updated user
				if (res.isOK()) {
					var newValue = JSON.encode(res.value());
					jedis.setex(key,EXPIRATION_TIME, newValue);
				}
				return res;
			} else {

				Result<User> res = errorOrResult( validatedUserOrError(DB.getOne( userId, User.class), pwd), user -> DB.updateOne( user.updateFrom(other)));

				if (res.isOK()) {
					Log.info(() -> String.format("\n\nPUTTING USER UPDATE IN CACHE: %s\n\n", res.value().getUserId()));
					var key1 = USER_PREFIX + res.value().getUserId();
					var value1 = JSON.encode(res.value());
					jedis.setex(key1,EXPIRATION_TIME, value1);
					//jedis.expire(key, EXPIRATION_TIME);

				}
				return res;
			}
		}
	}

	@Override
	public Result<User> deleteUser(String userId, String pwd) {
		Log.info(() -> format("deleteUser : userId = %s, pwd = %s\n", userId, pwd));

		if (userId == null || pwd == null )
			return error(BAD_REQUEST);

		Result<User> res =  DB.getOne( userId, User.class);

		return errorOrResult( validatedUserOrError(res, pwd), user -> {

			// Delete user shorts and related info asynchronously in a separate thread
			Executors.defaultThreadFactory().newThread( () -> {
				JavaBlobs.getInstance().deleteAllBlobs(userId, Token.get(userId));
			}).start();
			Executors.defaultThreadFactory().newThread( () -> {
				JavaShorts.getInstance().deleteAllShorts(userId, pwd, Token.get(userId));
			}).start();

			DB.deleteOne( user);

			Log.info(() -> String.format("\n\nDELETE USER (IN CACHE): %s\n\n", user.getUserId()));
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {
				if (res.isOK()) {
					var key = USER_PREFIX + res.value().getUserId();
					jedis.del(key);
				}
			}

			return DB.deleteOne( user);
		});
	}

	@Override
	public Result<List<User>> searchUsers(String pattern) {
		Log.info( () -> format("searchUsers : patterns = %s\n", pattern));

		var query = format("SELECT * FROM Users u WHERE UPPER(u.userId) LIKE '%%%s%%'", pattern.toUpperCase());
		var hits = DB.sql(query, User.class)
				.stream()
				.map(User::copyWithoutPassword)
				.toList();

		return ok(hits);
	}

	
	private Result<User> validatedUserOrError( Result<User> res, String pwd ) {
		if( res.isOK())
			return res.value().getPwd().equals( pwd ) ? res : error(FORBIDDEN);
		else
			return res;
	}
	
	private boolean badUserInfo( User user) {
		return (user.userId() == null || user.pwd() == null || user.displayName() == null || user.email() == null);
	}
	
	private boolean badUpdateUserInfo( String userId, String pwd, User info) {
		return (userId == null || pwd == null || info.getUserId() != null && ! userId.equals( info.getUserId()));
	}
}
