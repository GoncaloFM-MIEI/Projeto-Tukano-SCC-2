package tukano.impl;

import com.azure.cosmos.CosmosContainer;
import redis.clients.jedis.Jedis;
import tukano.api.Result;
import tukano.api.User;
import tukano.api.Users;
import utils.*;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static java.lang.String.format;
import static tukano.api.Result.ErrorCode.BAD_REQUEST;
import static tukano.api.Result.ErrorCode.FORBIDDEN;
import static tukano.api.Result.*;

public class JavaUsers implements Users {
	private final String USER_PREFIX = "user:";
	private final int EXPIRATION_TIME = 120;
	
	private static Logger Log = Logger.getLogger(JavaUsers.class.getName());

	private static Users instance;

	private static CosmosDBLayer cosmos;

	private static CosmosContainer usersContainer;

	private static final boolean isPostgree = "true".equalsIgnoreCase(Props.get("USE_POSTGREE", ""));
	private static final boolean hasCache = "true".equalsIgnoreCase(Props.get("HAS_CACHE", ""));
	
	synchronized public static Users getInstance() {
		if( instance == null )
			instance = new JavaUsers();
		return instance;
	}
	
	private JavaUsers() {
		if(!isPostgree){
			cosmos = CosmosDBLayer.getInstance();
			usersContainer = cosmos.getDB().getContainer(Users.NAME);
		}
	}



	@Override
	public Result<String> createUser(User user) {
		Log.info(() -> format("createUser : %s\n", user));

		if( badUserInfo( user ) ) {
			return error(BAD_REQUEST);
		}

		Locale.setDefault(Locale.US);
		Result<String> res;
		if(isPostgree){
			res = errorOrValue( DB.insertOne( user), user.getUserId());
		}else {
			res = errorOrValue(cosmos.insertOne(user, usersContainer), user.getUserId());
		}

		//Put the user in the cache after putting on DB
		if (res.isOK() && hasCache){
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

		//FOR okUser
		if(pwd.isEmpty()){
			return error(FORBIDDEN);
		}

		if(hasCache) {
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {

				var key = USER_PREFIX + userId;

				long startCacheTime = System.nanoTime();
				var value = jedis.get(key);


				//Get user from cache if exists
				if (value != null) {
					//TODO APENAS A CONTAGEM DO TEMPO
					long endCacheTime = System.nanoTime();
					Log.info(() -> String.format("\nCache Access Time: %d ms\n", (endCacheTime - startCacheTime) / 1_000_000)); // Convertendo para milissegundos
					return Result.ok(JSON.decode(value, User.class));
				}

				Result<User> u;
				if(isPostgree){
					u = validatedUserOrError( DB.getOne( userId, User.class), pwd);
				}else{
					u = validatedUserOrError(cosmos.getOne(userId, User.class, usersContainer), pwd);
				}
				//If user dont exist in the cache, after getting him from the DB just put it in the cache again
				if (u.isOK()) {
					Log.info(() -> String.format("\n\nPUTTING USER IN CACHE: %s\n\n", u.value().getUserId()));
					var user = JSON.encode(u.value());
					jedis.setex(key,EXPIRATION_TIME, user);

				}

				return u;
			}
		}else {
			long startDBTime = System.nanoTime();
			Result<User> result;

			if(isPostgree){
				result = validatedUserOrError( DB.getOne( userId, User.class), pwd);
			}else {
				result = validatedUserOrError(cosmos.getOne(userId, User.class, usersContainer), pwd);
			}
			long endDBTime = System.nanoTime();

			Log.info(() -> String.format("\nDB Access Time: %d ms\n", (endDBTime - startDBTime) / 1_000_000)); // Convertendo para milissegundos
			return result;
		}
	}

	@Override
	public Result<User> updateUser(String userId, String pwd, User other) {
		Log.info(() -> format("updateUser : userId = %s, pwd = %s, user: %s\n", userId, pwd, other));

		if (badUpdateUserInfo(userId, pwd, other))
			return error(BAD_REQUEST);

		if(hasCache) {
			try (Jedis jedis = RedisCache.getCachePool().getResource()) {

				var key = USER_PREFIX + userId;
				var value = jedis.get(key);

				//if user exists in cache just get him to avoid getting him from the DB
				if (value != null) {
					var user = JSON.decode(value, User.class);
					Log.info(() -> String.format("\n\nUPDATE USER (IN CACHE) (OLD USER): %s\n\n", user.getUserId()));
					Result<User> res;
					if(isPostgree){
						res = errorOrValue(DB.updateOne( user.updateFrom(other)), other);
					}
					else{
						res = errorOrValue(cosmos.updateOne(user.updateFrom(other), usersContainer), other);
					}

					//after updating, just overrite the same key with the updated user
					if (res.isOK()) {
						var newValue = JSON.encode(res.value());
						jedis.setex(key,EXPIRATION_TIME, newValue);
					}
					return res;
				} else {

					// if user don't exist in cache just get him and update him
					Result<User> res;
					if(isPostgree){
						res = errorOrResult( validatedUserOrError(DB.getOne( userId, User.class), pwd), user -> DB.updateOne( user.updateFrom(other)));
					}else{

						res = errorOrResult(validatedUserOrError(cosmos.getOne(userId, User.class, usersContainer), pwd), newUser -> cosmos.updateOne(newUser.updateFrom(other), usersContainer));
					}

					// after update put again on the cache
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
		}else{
			Result<User> res;
			if(isPostgree){
				res = errorOrResult( validatedUserOrError(DB.getOne( userId, User.class), pwd), user -> DB.updateOne( user.updateFrom(other)));
			}else{

				res = errorOrResult(validatedUserOrError(cosmos.getOne(userId, User.class, usersContainer), pwd), newUser -> cosmos.updateOne(newUser.updateFrom(other), usersContainer));
			}
			return res;
		}
	}

	@Override
	public Result<User> deleteUser(String userId, String pwd) {
		Log.info(() -> format("deleteUser : userId = %s, pwd = %s\n", userId, pwd));

		if (userId == null || pwd == null )
			return error(BAD_REQUEST);

		Result<User> res;
		if(isPostgree){
			res = DB.getOne( userId, User.class);
		}else{
			res = cosmos.getOne(userId, User.class, usersContainer);
		}

		//TODO ESCLARECER A SITUACÂO DO RETURN

		return errorOrResult( validatedUserOrError(res, pwd), user -> {

			// Delete user shorts and related info asynchronously in a separate thread
			Executors.defaultThreadFactory().newThread( () -> {
				JavaBlobs.getInstance().deleteAllBlobs(userId, Token.get(userId));
			}).start();
			Executors.defaultThreadFactory().newThread( () -> {
				JavaShorts.getInstance().deleteAllShorts(userId, pwd, Token.get(userId));
			}).start();

			if(isPostgree){
				DB.deleteOne( user);
			}else{
				cosmos.deleteOne(user, usersContainer);
			}
			//Result<User> res = cosmos.getOne(userId, User.class);
			//Delete user from cache
			if(hasCache) {
				Log.info(() -> String.format("\n\nDELETE USER (IN CACHE): %s\n\n", user.getUserId()));
				try (Jedis jedis = RedisCache.getCachePool().getResource()) {
					if (res.isOK()) {
						var key = USER_PREFIX + res.value().getUserId();
						jedis.del(key);
					}
				}
			}
			//	cosmos.getOne( userId, User.class);

			return res;
		});
	}

	//TODO FAZ SENTIDO TER CACHE????
	@Override
	public Result<List<User>> searchUsers(String pattern) {
		Log.info( () -> format("searchUsers : patterns = %s\n", pattern));

		//TODO TEMOS MESMO QUE TER O .TOUPPSERCASE() no patter? Não funciona com ele :)
		//TODO VER COMO FAZER A CENA DE RETORNAR MESMO O OBJETO USER

		if(isPostgree){
			var query = format("SELECT * FROM users u WHERE UPPER(u.id) LIKE '%%%s%%'", pattern.toUpperCase());
			var hits = DB.sql(query, User.class)
					.stream()
					.map(User::copyWithoutPassword)
					.toList();
			return ok(hits);
		}else{
			var query = format("SELECT * FROM users u WHERE u.id LIKE '%%%s%%'", pattern);
			var hits = cosmos.query(User.class, query, usersContainer).value()
							.stream()
							.map(User::copyWithoutPassword)
							.toList();

			return ok(hits);
		}
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
