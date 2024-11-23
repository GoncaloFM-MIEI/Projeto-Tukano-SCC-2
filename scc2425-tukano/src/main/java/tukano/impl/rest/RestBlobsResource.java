package tukano.impl.rest;

import jakarta.inject.Singleton;
import org.hsqldb.persist.Log;
import tukano.api.Blobs;
import tukano.api.rest.RestBlobs;
import tukano.impl.JavaBlobs;

import java.util.logging.Logger;

import static java.lang.String.format;

@Singleton
public class RestBlobsResource extends RestResource implements RestBlobs {

	final Blobs impl;

	private static Logger Log = Logger.getLogger(JavaBlobs.class.getName());
	
	public RestBlobsResource() {
		this.impl = JavaBlobs.getInstance();
	}
	
	@Override
	public void upload(String blobId, byte[] bytes, String token) {
		super.resultOrThrow( impl.upload(blobId, bytes, token));
	}

	@Override
	public byte[] download(String blobId, String token) {
		return super.resultOrThrow( impl.download( blobId, token ));
	}

	@Override
	public void delete(String blobId, String token) {
		Log.info(() -> format("ENTROU"));
		super.resultOrThrow( impl.delete( blobId, token ));
	}
	
	@Override
	public void deleteAllBlobs(String userId, String password) {
		super.resultOrThrow( impl.deleteAllBlobs( userId, password ));
	}
}
