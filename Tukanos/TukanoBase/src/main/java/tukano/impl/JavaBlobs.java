package tukano.impl;

import static java.lang.String.format;
import static tukano.api.Result.ErrorCode.UNAUTHORIZED;
import static tukano.api.Result.error;
import static tukano.api.Result.ErrorCode.FORBIDDEN;

import java.util.Objects;
import java.util.logging.Logger;

import jakarta.ws.rs.NotAuthorizedException;
import redis.clients.jedis.Jedis;
import tukano.api.Blobs;
import tukano.api.Result;
import tukano.impl.auth.RequestCookies;
import tukano.impl.rest.TukanoRestServer;
import tukano.impl.storage.BlobStorage;
import tukano.impl.storage.FilesystemStorage;
import utils.Hash;
import utils.Hex;

import jakarta.ws.rs.core.Cookie;
import utils.JSON;
import utils.RedisCache;

public class JavaBlobs implements Blobs {
	
	private static Blobs instance;
	private static Logger Log = Logger.getLogger(JavaBlobs.class.getName());

	public String baseURI;
	private BlobStorage storage;

	static final String COOKIE_KEY = "scc:session";
	static final String SESSION_PREFIX = "session:";
	static final String ADMIN = "Admin";

	synchronized public static Blobs getInstance() {
		if( instance == null )
			instance = new JavaBlobs();
		return instance;
	}
	
	private JavaBlobs() {
		storage = new FilesystemStorage();
		baseURI = String.format("%s/%s/", TukanoRestServer.serverURI, Blobs.NAME);
	}
	
	@Override
	public Result<Void> upload(String blobId, byte[] bytes, String token) {
		Log.info(() -> format("upload : blobId = %s, sha256 = %s, token = %s\n", blobId, Hex.of(Hash.sha256(bytes)), token));

		if (!validBlobId(blobId, token))
			return error(FORBIDDEN);

		if(!validateSession(blobId.split("\\+")[0])){
			return error(UNAUTHORIZED);
		}

		return storage.write( toPath( blobId ), bytes);
	}

	@Override
	public Result<byte[]> download(String blobId, String token) {
		Log.info(() -> format("download : blobId = %s, token=%s\n", blobId, token));

		if( ! validBlobId( blobId, token ) )
			return error(FORBIDDEN);

		if(!validateSessionCookie()){
			return error(UNAUTHORIZED);
		}

		return storage.read( toPath( blobId ) );
	}

	@Override
	public Result<Void> delete(String blobId, String token) {
		Log.info(() -> format("delete : blobId = %s, token=%s\n", blobId, token));
	
		if( ! validBlobId( blobId, token ) )
			return error(FORBIDDEN);

		if(!validateAdminSessionCookie()){
			return error(UNAUTHORIZED);
		}

		return storage.delete( toPath(blobId));
	}
	
	@Override
	public Result<Void> deleteAllBlobs(String userId, String token) {
		Log.info(() -> format("deleteAllBlobs : userId = %s, token=%s\n", userId, token));

		if( ! Token.isValid( token, userId ) )
			return error(FORBIDDEN);

		if(!validateAdminSessionCookie()){
			return error(UNAUTHORIZED);
		}
		
		return storage.delete( toPath(userId));
	}
	
	private boolean validBlobId(String blobId, String token) {		
		return Token.isValid(token, blobId);
	}

	private String toPath(String blobId) {
		return blobId.replace("+", "/");
	}

	public boolean validateSession(String userId) throws NotAuthorizedException {
		var cookies = RequestCookies.get();
		return validateSession( cookies.get(COOKIE_KEY), userId );
	}

	public boolean validateSession(Cookie cookie, String userId) throws NotAuthorizedException {

		if (cookie == null ) {
			throw new NotAuthorizedException("No session initialized");
		}

		try (Jedis jedis = RedisCache.getCachePool().getResource()) {
			var key = SESSION_PREFIX + cookie.getValue();

			var value = jedis.get(key);

			if(value == null) {
				throw new NotAuthorizedException("No valid session initialized");
				//return false;
			}

			if(!JSON.decode(value, String.class).equals(userId)){
				throw new NotAuthorizedException("Invalid session");
				//return false;
			}

		}

		Log.info(() -> "USER ESTA BEM LOGADO");

		return true;
	}

	public boolean validateSessionCookie() throws NotAuthorizedException {
		var cookies = RequestCookies.get();
		return validateSessionCookie( cookies.get(COOKIE_KEY));
	}

	public boolean validateSessionCookie(Cookie cookie) throws NotAuthorizedException {

		if (cookie == null )
			throw new NotAuthorizedException("No session initialized");
			//return false;

		try (Jedis jedis = RedisCache.getCachePool().getResource()) {
			var key = SESSION_PREFIX + cookie.getValue();

			var value = jedis.get(key);

			if(value == null) {
				//return false;
				throw new NotAuthorizedException("No valid session initialized");
			}

		}

		return true;
	}

	public boolean validateAdminSessionCookie() throws NotAuthorizedException {
		var cookies = RequestCookies.get();
		//try{
		return validateAdminSessionCookie( cookies.get(COOKIE_KEY) );
		//}catch(NotAuthorizedException e){
		//	return false;
		//}
	}

	public boolean validateAdminSessionCookie(Cookie cookie) throws NotAuthorizedException {

		if (cookie == null ){
			throw new NotAuthorizedException("No session initialized");
		}

			//throw new NotAuthorizedException("No session initialized");

		try (Jedis jedis = RedisCache.getCachePool().getResource()) {
			var key = SESSION_PREFIX + cookie.getValue();

			var value = jedis.get(key);

			Log.info(() -> String.format("\n\nValue: %s\n\n", value));

			if(value == null) {
				return false;
			}

			if(!Objects.equals(JSON.decode(value, String.class), ADMIN)){
				//return false;
				throw new NotAuthorizedException("You are not admin");
			}

		}

		Log.info(() -> "USER ADMIN ESTA BEM LOGADO");

		return true;
	}
}
