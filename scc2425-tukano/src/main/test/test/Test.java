package test;

import java.nio.ByteBuffer;
import java.util.Random;

import tukano.api.Result;
import tukano.rest.RestBlobsClient;
import tukano.rest.RestShortsClient;
import tukano.rest.RestUsersClient;
import tukano.impl.rest.TukanoRestServer;

public class Test {
	
	static {
		System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s");
	}
	
	public static void main(String[] args ) throws Exception {
		new Thread( () -> {	
			try { 
				TukanoRestServer.main( new String[] {} );
			} catch( Exception x ) {
				x.printStackTrace();
			}
		}).start();

		
		Thread.sleep(1000);
		
		var serverURI = String.format("http://localhost:%s/rest", TukanoRestServer.PORT);
		
		var blobs = new RestBlobsClient(serverURI);
		var users = new RestUsersClient(serverURI);
		var shorts = new RestShortsClient(serverURI);

		//TODO Users operations test
//		show(users.createUser( new User("wales", "12345", "wjimmy@wikipedia.pt", "Jimmy Wales") ));
//		show(users.createUser( new User("liskov", "54321", "liskov@mit.edu", "Barbara Liskov") ));
//		show(users.createUser( new User("liskov123", "54321", "liskov@mit.edu", "Barbara Liskov") ));
//		show(users.getUser("liskov", "54321"));
//		show(users.updateUser("wales", "12345", new User("wales", "12345", "jimmy@gmail.com", "" ) ));
//		show(users.deleteUser("liskov123", "54321"));
//		show(users.searchUsers(""));
//		show(users.searchUsers("wal"));


		//TODO Shorts operations test
		Result<tukano.api.Short> s1, s2;

		show(s2 = shorts.createShort("liskov", "54321"));
		show(s1 = shorts.createShort("wales", "12345"));
		show(shorts.createShort("wales", "12345"));
		show(shorts.createShort("wales", "12345"));
		show(shorts.createShort("wales", "12345"));

		var s1id = s1.value().getShortId();
		var s2id = s2.value().getShortId();
		show(shorts.getShort(s1id));

//		show(shorts.deleteShort(s2id, "54321"));

		show(shorts.getShorts( "wales"));


//		var blobUrl = URI.create(s2.value().getBlobUrl());
//		System.out.println( "------->" + blobUrl );
//
//		var blobId = new File( blobUrl.getPath() ).getName();
//		System.out.println( "BlobID:" + blobId );
//
//		var token = blobUrl.getQuery().split("=")[1];
//
//		blobs.upload(blobUrl.toString(), randomBytes( 100 ), token);
//
//		//--------------TESTING DOWNLOAD
//		System.out.println( "------->" + blobUrl );
//		System.out.println( "DOWNLOADING BLOBS BlobID:" + blobId );
//
//		show(blobs.download(blobUrl.toString(), token));

		//---------------TESTING DELETE-----------------
		//System.out.println( "------->" + blobUrl );
		//System.out.println( "DELETING BlobID:" + blobId );
		//Thread.sleep(5000);

		//show(blobs.delete(blobUrl.toString(), token));
		//System.out.println("PASSAMOS O .delete");
		//---------------TESTING DELETE-----------------
		
//		var s2id = s2.value().getShortId();
//
		show(shorts.follow("liskov", "wales", true, "54321"));
		show(shorts.follow("wales", "liskov", true, "12345"));
		show(shorts.followers("wales", "12345"));

		show(shorts.like(s2id, "wales", true, "12345"));
		show(shorts.like(s2id, "liskov", true, "54321"));
		show(shorts.likes(s2id , "54321"));

		show(shorts.getShort(s2id));
		show(shorts.getFeed("liskov", "12345"));
//		show(shorts.getShort( s2id ));
//
//
//		show(shorts.followers("wales", "12345"));
//
//		show(shorts.getFeed("liskov", "12345"));
//
//		show(shorts.getShort( s2id ));
//
//		show(shorts.deleteShort(s2id, "54321"));
////
//		
//		blobs.forEach( b -> {
//			var r = b.download(blobId);
//			System.out.println( Hex.of(Hash.sha256( bytes )) + "-->" + Hex.of(Hash.sha256( r.value() )));
//			
//		});

//		show(users.updateUser("wales","12345", new User("wales", "12345", "jimmy@marco.pt", "Jimmy Wales")));
//		show(users.searchUsers("lis"));
//	 	show(users.deleteUser("liskov", "54321"));
	 	//show(blobs.deleteAllBlobs("liskov", Token.get("liskov")));

		System.exit(0);
	}
	
	
	private static Result<?> show( Result<?> res ) {
		if( res.isOK() )
			System.err.println("OK: " + res.value() );
		else
			System.err.println("ERROR:" + res.error());
		return res;
		
	}
	
	private static byte[] randomBytes(int size) {
		var r = new Random(1L);

		var bb = ByteBuffer.allocate(size);
		
		r.ints(size).forEach( i -> bb.put( (byte)(i & 0xFF)));		

		return bb.array();
		
	}
}
