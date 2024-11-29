package utils;


import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisCache {
    private static final String REDIS_HOST = System.getenv("REDIS_HOST"); // Kubernetes Service name
    private static final int REDIS_PORT = Integer.parseInt(System.getenv("REDIS_PORT"));
    private static final int REDIS_TIMEOUT = 10000;
    private static final boolean Redis_USE_TLS = false;

    private static JedisPool instance;

    public synchronized static JedisPool getCachePool() {
        if (instance != null)
            return instance;

        var poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(128);
        poolConfig.setMaxIdle(128);
        poolConfig.setMinIdle(16);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setNumTestsPerEvictionRun(3);
        poolConfig.setBlockWhenExhausted(true);
        instance = new JedisPool(poolConfig, REDIS_HOST, REDIS_PORT, REDIS_TIMEOUT, Redis_USE_TLS);
        return instance;
    }
}
