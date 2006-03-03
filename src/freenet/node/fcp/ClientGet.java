package freenet.node.fcp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.FetcherContext;
import freenet.client.InserterException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientCallback;
import freenet.client.async.ClientGetter;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;

/**
 * A simple client fetch. This can of course fetch arbitrarily large
 * files, including splitfiles, redirects, etc.
 */
public class ClientGet extends ClientRequest implements ClientCallback, ClientEventListener {

	private final FreenetURI uri;
	private final FetcherContext fctx;
	private final String identifier;
	private final int verbosity;
	/** Original FCPConnectionHandler. Null if persistence != connection */
	private final FCPConnectionHandler origHandler;
	private final FCPClient client;
	private final ClientGetter getter;
	private final short priorityClass;
	private final short returnType;
	private final short persistenceType;
	/** Has the request finished? */
	private boolean finished;
	private final File targetFile;
	private final File tempFile;
	final String clientToken;
	
	// Verbosity bitmasks
	private int VERBOSITY_SPLITFILE_PROGRESS = 1;
	
	// Stuff waiting for reconnection
	/** Did the request succeed? Valid if finished. */
	private boolean succeeded;
	/** Length of the found data */
	private long foundDataLength;
	/** MIME type of the found data */
	private String foundDataMimeType;
	/** Details of request failure */
	private GetFailedMessage getFailedMessage;
	/** Succeeded but failed to return data e.g. couldn't write to file */
	private ProtocolErrorMessage postFetchProtocolErrorMessage;
	/** AllData (the actual direct-send data) - do not persist, because the bucket
	 * is not persistent. FIXME make the bucket persistent! */
	private AllDataMessage allDataPending;
	/** Last progress message. Not persistent - FIXME this will be made persistent
	 * when we have proper persistence at the ClientGetter level. */
	private SimpleProgressMessage progressPending;
	
	public ClientGet(FCPConnectionHandler handler, ClientGetMessage message) {
		uri = message.uri;
		clientToken = message.clientToken;
		// FIXME
		this.priorityClass = message.priorityClass;
		this.persistenceType = message.persistenceType;
		// Create a Fetcher directly in order to get more fine-grained control,
		// since the client may override a few context elements.
		if(persistenceType == PERSIST_CONNECTION)
			this.origHandler = handler;
		else
			origHandler = null;
		this.client = handler.getClient();
		fctx = new FetcherContext(client.defaultFetchContext, FetcherContext.IDENTICAL_MASK);
		fctx.eventProducer.addEventListener(this);
		// ignoreDS
		fctx.localRequestOnly = message.dsOnly;
		fctx.ignoreStore = message.ignoreDS;
		fctx.maxNonSplitfileRetries = message.maxRetries;
		fctx.maxSplitfileBlockRetries = message.maxRetries;
		this.identifier = message.identifier;
		this.verbosity = message.verbosity;
		// FIXME do something with verbosity !!
		// Has already been checked
		this.returnType = message.returnType;
		fctx.maxOutputLength = message.maxSize;
		fctx.maxTempLength = message.maxTempSize;
		this.targetFile = message.diskFile;
		this.tempFile = message.tempFile;
		getter = new ClientGetter(this, client.node.fetchScheduler, uri, fctx, priorityClass, client);
	}

	/**
	 * Create a ClientGet from a request serialized to a SimpleFieldSet.
	 * Can throw, and does minimal verification, as is dealing with data 
	 * supposedly serialized out by the node.
	 * @throws MalformedURLException 
	 */
	public ClientGet(SimpleFieldSet fs, FCPClient client2) throws MalformedURLException {
		uri = new FreenetURI(fs.get("URI"));
		identifier = fs.get("Identifier");
		verbosity = Integer.parseInt(fs.get("Verbosity"));
		priorityClass = Short.parseShort(fs.get("PriorityClass"));
		returnType = ClientGetMessage.parseValidReturnType(fs.get("ReturnType"));
		persistenceType = ClientRequest.parsePersistence(fs.get("Persistence"));
		if(persistenceType == ClientRequest.PERSIST_CONNECTION)
			throw new IllegalArgumentException("Reading persistent get with type CONNECTION !!");
		if(!(persistenceType == ClientRequest.PERSIST_FOREVER || persistenceType == ClientRequest.PERSIST_REBOOT))
			throw new IllegalArgumentException("Unknown persistence type "+ClientRequest.persistenceTypeString(persistenceType));
		this.client = client2;
		this.origHandler = null;
		String f = fs.get("Filename");
		if(f != null)
			targetFile = new File(f);
		else
			targetFile = null;
		f = fs.get("TempFilename");
		if(f != null)
			tempFile = new File(f);
		else
			tempFile = null;
		clientToken = fs.get("ClientToken");
		finished = Fields.stringToBool(fs.get("Finished"), false);
		boolean ignoreDS = Fields.stringToBool(fs.get("IgnoreDS"), false);
		boolean dsOnly = Fields.stringToBool(fs.get("DSOnly"), false);
		int maxRetries = Integer.parseInt(fs.get("MaxRetries"));
		fctx = new FetcherContext(client.defaultFetchContext, FetcherContext.IDENTICAL_MASK);
		fctx.eventProducer.addEventListener(this);
		// ignoreDS
		fctx.localRequestOnly = dsOnly;
		fctx.ignoreStore = ignoreDS;
		fctx.maxNonSplitfileRetries = maxRetries;
		fctx.maxSplitfileBlockRetries = maxRetries;
		succeeded = Fields.stringToBool(fs.get("Succeeded"), false);
		if(finished) {
			if(succeeded) {
				foundDataLength = Long.parseLong(fs.get("FoundDataLength"));
				foundDataMimeType = fs.get("FoundDataMimeType");
				SimpleFieldSet fs1 = fs.subset("PostFetchProtocolError");
				if(fs1 != null)
					postFetchProtocolErrorMessage = new ProtocolErrorMessage(fs1);
			} else {
				getFailedMessage = new GetFailedMessage(fs.subset("GetFailed"), false);
			}
		}
		
		getter = new ClientGetter(this, client.node.fetchScheduler, uri, fctx, priorityClass, client);
		start();
	}

	void start() {
		try {
			getter.start();
		} catch (FetchException e) {
			onFailure(e, null);
		}
	}
	
	public void onLostConnection() {
		if(persistenceType == PERSIST_CONNECTION)
			cancel();
		// Otherwise ignore
	}
	
	public void cancel() {
		getter.cancel();
	}

	public void onSuccess(FetchResult result, ClientGetter state) {
		Bucket data = result.asBucket();
		boolean dontFree = false;
		// FIXME I don't think this is a problem in this case...? (Disk write while locked..)
		synchronized(this) {
			if(returnType == ClientGetMessage.RETURN_TYPE_DIRECT) {
				// Send all the data at once
				// FIXME there should be other options
				trySendDataFoundOrGetFailed();
				AllDataMessage m = new AllDataMessage(data, identifier);
				if(persistenceType == PERSIST_CONNECTION)
					m.setFreeOnSent();
				dontFree = true;
				trySendAllDataMessage(m);
			} else if(returnType == ClientGetMessage.RETURN_TYPE_NONE) {
				// Do nothing
			} else if(returnType == ClientGetMessage.RETURN_TYPE_DISK) {
				// Write to temp file, then rename over filename
				FileOutputStream fos = null;
				try {
					fos = new FileOutputStream(tempFile);
					BucketTools.copyTo(data, fos, data.size());
					if(!tempFile.renameTo(targetFile)) {
						postFetchProtocolErrorMessage = new ProtocolErrorMessage(ProtocolErrorMessage.COULD_NOT_RENAME_FILE, false, null, identifier);
						trySendDataFoundOrGetFailed();
						// Don't delete temp file, user might want it.
					}
				} catch (FileNotFoundException e) {
					postFetchProtocolErrorMessage = new ProtocolErrorMessage(ProtocolErrorMessage.COULD_NOT_WRITE_FILE, false, null, identifier);
				} catch (IOException e) {
					postFetchProtocolErrorMessage = new ProtocolErrorMessage(ProtocolErrorMessage.COULD_NOT_WRITE_FILE, false, null, identifier);
				}
				try {
					if(fos != null)
						fos.close();
				} catch (IOException e) {
					Logger.error(this, "Caught "+e+" closing file "+tempFile, e);
				}
			}
			progressPending = null;
			FCPMessage msg = new DataFoundMessage(result, identifier);
			this.foundDataLength = data.size();
			this.foundDataMimeType = result.getMimeType();
			this.succeeded = true;
			finished = true;
		}
		trySendDataFoundOrGetFailed();
		if(!dontFree)
			data.free();
		finish();
	}

	private void trySendDataFoundOrGetFailed() {
		
		FCPMessage msg;

		if(postFetchProtocolErrorMessage != null) {
			msg = postFetchProtocolErrorMessage;
		} else if(succeeded) {
			msg = new DataFoundMessage(foundDataLength, foundDataMimeType, identifier);
		} else {
			msg = getFailedMessage;
		}
		
		FCPConnectionHandler conn = client.getConnection();
		if(conn != null)
			conn.outputHandler.queue(msg);
	}

	private void trySendAllDataMessage(AllDataMessage msg) {
		if(persistenceType != ClientRequest.PERSIST_CONNECTION) {
			allDataPending = msg;
		}
		FCPConnectionHandler conn = client.getConnection();
		if(conn != null)
			conn.outputHandler.queue(msg);
	}
	
	private void trySendProgress(SimpleProgressMessage msg) {
		if(persistenceType != ClientRequest.PERSIST_CONNECTION) {
			progressPending = msg;
		}
		FCPConnectionHandler conn = client.getConnection();
		if(conn != null)
			conn.outputHandler.queue(msg);
	}
	
	public void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest) {
		if(persistenceType == ClientRequest.PERSIST_CONNECTION) {
			Logger.error(this, "WTF? persistenceType="+persistenceType, new Exception("error"));
			return;
		}
		if(includePersistentRequest) {
			FCPMessage msg = new PersistentGet(identifier, uri, verbosity, priorityClass, returnType, persistenceType, targetFile, tempFile, clientToken);
			handler.queue(msg);
		}
		if(progressPending != null)
			handler.queue(progressPending);
		if(finished)
			trySendDataFoundOrGetFailed();
		if(allDataPending != null)
			handler.queue(allDataPending);
	}

	public void onFailure(FetchException e, ClientGetter state) {
		synchronized(this) {
			succeeded = false;
			getFailedMessage = new GetFailedMessage(e, identifier);
			finished = true;
		}
		Logger.minor(this, "Caught "+e, e);
		trySendDataFoundOrGetFailed();
		finish();
	}

	/** Request completed. But we may have to stick around until we are acked. */
	private void finish() {
		if(persistenceType == ClientRequest.PERSIST_CONNECTION) {
			origHandler.finishedClientRequest(this);
		} else {
			client.finishedClientRequest(this);
		}
	}

	public void onSuccess(BaseClientPutter state) {
		// Ignore
	}

	public void onFailure(InserterException e, BaseClientPutter state) {
		// Ignore
	}

	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state) {
		// Ignore
	}

	public void receive(ClientEvent ce) {
		if(finished) return;
		if(!(((verbosity & VERBOSITY_SPLITFILE_PROGRESS) == VERBOSITY_SPLITFILE_PROGRESS) &&
				(ce instanceof SplitfileProgressEvent)))
			return;
		SimpleProgressMessage progress = 
			new SimpleProgressMessage(identifier, (SplitfileProgressEvent)ce);
		trySendProgress(progress);
	}

	public boolean isPersistent() {
		return persistenceType != ClientRequest.PERSIST_CONNECTION;
	}

	public void dropped() {
		cancel();
		if(allDataPending != null)
			allDataPending.bucket.free();
	}

	public String getIdentifier() {
		return identifier;
	}

	public void write(BufferedWriter w) throws IOException {
		if(persistenceType != ClientRequest.PERSIST_REBOOT) {
			Logger.error(this, "Not persisting as persistenceType="+persistenceType);
		}
		// Persist the request to disk
		SimpleFieldSet fs = getFieldSet();
		fs.writeTo(w);
	}
	
	// This is distinct from the ClientGetMessage code, as later on it will be radically
	// different (it can store detailed state).
	public synchronized SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true); // we will need multi-level later...
		fs.put("Type", "GET");
		fs.put("URI", uri.toString(false));
		fs.put("Identifier", identifier);
		fs.put("Verbosity", Integer.toString(verbosity));
		fs.put("PriorityClass", Short.toString(priorityClass));
		fs.put("ReturnType", ClientGetMessage.returnTypeString(returnType));
		fs.put("Persistence", ClientRequest.persistenceTypeString(persistenceType));
		fs.put("ClientName", client.name);
		if(targetFile != null)
			fs.put("Filename", targetFile.getPath());
		if(tempFile != null)
			fs.put("TempFilename", tempFile.getPath());
		if(clientToken != null)
			fs.put("ClientToken", clientToken);
		if(returnType == ClientGetMessage.RETURN_TYPE_DISK && targetFile != null) {
			// Otherwise we must re-run it anyway as we don't have the data.
			
			// finished => persistence of completion state, pending messages
			//fs.put("Finished", Boolean.toString(finished));
		}
		fs.put("IgnoreDS", Boolean.toString(fctx.ignoreStore));
		fs.put("DSOnly", Boolean.toString(fctx.localRequestOnly));
		fs.put("MaxRetries", Integer.toString(fctx.maxNonSplitfileRetries));
		fs.put("Finished", Boolean.toString(finished));
		fs.put("Succeeded", Boolean.toString(succeeded));
		if(finished) {
			if(succeeded) {
				fs.put("FoundDataLength", Long.toString(foundDataLength));
				fs.put("FoundDataMimeType", foundDataMimeType);
				if(postFetchProtocolErrorMessage != null) {
					fs.put("PostFetchProtocolError", postFetchProtocolErrorMessage.getFieldSet());
				}
			} else {
				if(getFailedMessage != null) {
					fs.put("GetFailed", getFailedMessage.getFieldSet(false));
				}
			}
		}
		return fs;
	}

	public boolean hasFinished() {
		return finished;
	}

	public boolean isPersistentForever() {
		return persistenceType == ClientRequest.PERSIST_FOREVER;
	}

}
