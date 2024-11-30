package tukano.impl;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import redis.clients.jedis.Jedis;
import tukano.api.Result;
import tukano.api.User;
import tukano.impl.auth.RequestCookies;
import utils.RedisCache;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response.Status;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import utils.JSON;

@Path(JavaLogin.PATH)
public class JavaLogin {
	static final String PATH = "/login";
	static final String USER_ID = "userId";
	static final String PWD = "pwd";
	static final String SESSION_PREFIX = "session:";
	static final String COOKIE_KEY = "scc:session";
	private static final int MAX_COOKIE_AGE = 30;

	@POST
	@Path("/{" + USER_ID + "}")
	public Response login(@PathParam(USER_ID) String userId, @QueryParam(PWD) String pwd ) {
		System.out.println("user: " + userId + " pwd:" + pwd);

		boolean pwdOk = Objects.equals(pwd, okUser(userId, pwd).value().pwd());

		if (pwdOk) {

			String uid = UUID.randomUUID().toString();
			var cookie = new NewCookie.Builder(COOKIE_KEY)
					.value(uid).path("/")
					.comment("sessionid")
					.maxAge(MAX_COOKIE_AGE)
					.secure(false)
					.httpOnly(true)
					.build();

			try (Jedis jedis = RedisCache.getCachePool().getResource()) {
				var key = SESSION_PREFIX + uid;

				jedis.setex(key, MAX_COOKIE_AGE, JSON.encode(userId));
			}

            return Response.ok()
                    .cookie(cookie)
                    .build();
		} else
			throw new NotAuthorizedException("Incorrect login");
	}

	protected Result<User> okUser(String userId, String pwd) {
		return JavaUsers.getInstance().getUser(userId, pwd);
	}

}
