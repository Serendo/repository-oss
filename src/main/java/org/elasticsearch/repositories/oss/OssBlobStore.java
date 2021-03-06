package org.elasticsearch.repositories.oss;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.*;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.common.blobstore.*;
import org.elasticsearch.common.blobstore.support.PlainBlobMetaData;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class OssBlobStore extends AbstractComponent implements BlobStore {

	private final OssStorageService ossStorageService;
	private final String bucket;

	public OssBlobStore(Settings settings, String bucket, OssStorageService ossStorageService) {
		super(settings);
		this.ossStorageService = ossStorageService;
		this.bucket = bucket;
		if (!doesBucketExist(bucket)) {
			throw new BlobStoreException("Bucket [" + bucket + "] does not exist");
		}
	}

	public String getBucket() {
		return this.bucket;
	}

	@Override
	public BlobContainer blobContainer(BlobPath blobPath) {
		return new OssBlobContainer(blobPath, this);
	}

	@Override
	public void delete(BlobPath blobPath) throws IOException {
		SocketAccess.doPrivilegedVoidException(() -> {
			DeleteObjectsRequest deleteRequest = new DeleteObjectsRequest(bucket);
			Map<String, BlobMetaData> blobs = listBlobsByPrefix(blobPath.buildAsString(), null);
			List<String> toBeDeletedBlobs = new ArrayList<>();
			Iterator<String> blobNameIterator = blobs.keySet().iterator();
			while (blobNameIterator.hasNext()) {
				String blobName = blobNameIterator.next();
				toBeDeletedBlobs.add(blobPath.buildAsString() + blobName);
				if (toBeDeletedBlobs.size() > DeleteObjectsRequest.DELETE_OBJECTS_ONETIME_LIMIT / 2
						|| !blobNameIterator.hasNext()) {
					deleteRequest.setKeys(toBeDeletedBlobs);
					this.ossStorageService.deleteObjects(deleteRequest);
					toBeDeletedBlobs.clear();
				}
			}
		});
	}

	@Override
	public void close() throws IOException {
		ossStorageService.shutdown();
	}

	boolean doesBucketExist(String bucketName) {
		return this.ossStorageService.doesBucketExist(bucketName);
	}

	Map<String, BlobMetaData> listBlobsByPrefix(String keyPath, String prefix) throws IOException {
		return SocketAccess.doPrivilegedException(() -> {
			MapBuilder<String, BlobMetaData> blobsBuilder = MapBuilder.newMapBuilder();
			String actualPrefix = keyPath + (prefix == null ? StringUtils.EMPTY : prefix);
			String nextMarker = null;
			ObjectListing blobs;
			do {
				blobs = this.ossStorageService
						.listObjects(new ListObjectsRequest(bucket).withPrefix(actualPrefix).withMarker(nextMarker));
				for (OSSObjectSummary summary : blobs.getObjectSummaries()) {
					String blobName = summary.getKey().substring(keyPath.length());
					blobsBuilder.put(blobName, new PlainBlobMetaData(blobName, summary.getSize()));
				}
				nextMarker = blobs.getNextMarker();
			} while (blobs.isTruncated());
			return blobsBuilder.immutableMap();
		});
	}

	boolean blobExists(String blobName) throws OSSException, ClientException, IOException {
		return SocketAccess.doPrivilegedException(() -> this.ossStorageService.doesObjectExist(bucket, blobName));
	}

	InputStream readBlob(String blobName) throws OSSException, ClientException, IOException {
		return SocketAccess.doPrivilegedException(() -> this.ossStorageService.getObject(bucket, blobName).getObjectContent());
	}

	void writeBlob(String blobName, InputStream inputStream, long blobSize, boolean failIfAlreadyExists)
			throws OSSException, ClientException, IOException {

		ObjectMetadata meta = new ObjectMetadata();
		meta.setContentLength(blobSize);
		SocketAccess.doPrivilegedException(() -> this.ossStorageService.putObject(bucket, blobName, inputStream, meta, failIfAlreadyExists));
	}

	void deleteBlob(String blobName) throws OSSException, ClientException {
		SocketAccess.doPrivilegedVoidException(() -> {
			this.ossStorageService.deleteObject(bucket, blobName);
		});
	}

	public void move(String sourceBlobName, String targetBlobName) throws OSSException, ClientException, IOException {
		SocketAccess.doPrivilegedException(() -> {
			this.ossStorageService.copyObject(bucket, sourceBlobName, bucket, targetBlobName);
			this.ossStorageService.deleteObject(bucket, sourceBlobName);
			return null;
		});
	}
}
