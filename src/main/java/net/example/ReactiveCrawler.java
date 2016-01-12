package net.example;

import static java.util.Arrays.asList;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bson.Document;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.rx.RxClient;
import org.glassfish.jersey.client.rx.rxjava.RxObservable;
import org.glassfish.jersey.client.rx.rxjava.RxObservableInvoker;

import com.mongodb.ServerAddress;
import com.mongodb.async.client.MongoClientSettings;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.connection.ClusterSettings;
import com.mongodb.connection.ConnectionPoolSettings;
import com.mongodb.rx.client.MongoClient;
import com.mongodb.rx.client.MongoClients;
import com.mongodb.rx.client.MongoCollection;
import com.mongodb.rx.client.MongoDatabase;

import rx.Notification;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

/**
 * Created by jah on 12/25/15.
 */
public class ReactiveCrawler {

	public static final String FIELD_NAME_URL = "url";

	public static void main(String[] args) throws InterruptedException {

		CountDownLatch cl = new CountDownLatch(1);

		RxClient<RxObservableInvoker> client = RxObservable.newClient(Executors.newFixedThreadPool(1));

		MongoCollection<Document> linkCollection = setupLinkCollection();

		//String initialLink = "http://localhost:8888/testImgTagBasicAuth.html";
		String initialLink = "http://www.zeit.de/index";

		linkCollection.insertOne(new Document("url", initialLink)).toBlocking().single();

		
		Observable.interval(200, TimeUnit.MILLISECONDS)
			.flatMap(tick -> links(linkCollection)).doOnEach(debug("GET", ""))
				.flatMap(linkDoc -> httpGet(client, linkDoc)).subscribeOn(Schedulers.io())
				.map(HtmlLinkExtractor::parseLinks).map(ReactiveCrawler::linksAsDocuments).subscribeOn(Schedulers.io())
				.flatMap(docs -> persist(linkCollection, null, docs))
				.subscribe();

		cl.await();
	}



	static Observable<String> httpGet(RxClient<RxObservableInvoker> client, Document url) {
		System.out.println("Getting with"+Thread.currentThread());
		return client
				.target(url.getString("url"))
				.property(ClientProperties.FOLLOW_REDIRECTS, Boolean.TRUE)
				.request().rx().get(String.class).onErrorResumeNext(throwable -> Observable.never());
	}



	static public Observable<Document> links(MongoCollection<Document> linkCollection) {

		return linkCollection.findOneAndUpdate(Filters.ne("status", "DONE"),
				new Document("$set", new Document("status", "DONE")),
				new FindOneAndUpdateOptions().sort(Sorts.ascending(FIELD_NAME_URL)));
	}

	
	static public Observable<UpdateResult> persist(MongoCollection<Document> linkCollection, Document linkDoc, List<Document> documents) {
		System.out.println("Persisting with"+Thread.currentThread());
		Func1<Document, Observable<UpdateResult>> insert =
				doc -> linkCollection.updateOne(
						Filters.eq("url", doc.getString("url")),
						new Document("$set", doc).append("$currentDate", new Document("lastVisit", new Document("$type", "timestamp") ) ),
						new UpdateOptions().upsert(true));

		return Observable.from(documents).flatMap(insert);

	}


	private static MongoCollection<Document> setupLinkCollection() {

		ClusterSettings clusterSettings = ClusterSettings.builder()
				.hosts(asList(new ServerAddress("192.168.99.105:27017"))).build();
		ConnectionPoolSettings connectionPoolSettings = ConnectionPoolSettings.builder().maxSize(500)
				.maxWaitQueueSize(1000).build();

		MongoClientSettings settings = MongoClientSettings.builder().clusterSettings(clusterSettings)
				.connectionPoolSettings(connectionPoolSettings).build();

		MongoClient mongoClient = MongoClients.create(settings);
		MongoDatabase database = mongoClient.getDatabase("crawler");
		MongoCollection<Document> collection = database.getCollection("links");

		cleanup: {
			collection.deleteMany(new Document()).toBlocking().single();
		}

		return collection;
	}


	static public List<Document> linksAsDocuments(List<HtmlLinkExtractor.HtmlLink> links) {

		return links.stream()
				.filter(link -> link.getLink().startsWith("http://"))
				.collect(ArrayList::new,
						(list, link) -> list.add(new Document("url", link.getLink()).append("linktext", link.getLinkText())),
						ArrayList::addAll);
	}




	static <T> Action1<Notification<? super T>> debug(String description, String offset) {

		AtomicReference<String> nextOffset = new AtomicReference<String>(">");
		return (Notification<? super T> notification) -> {

			switch (notification.getKind()) {
			case OnNext:
				System.out.println(Thread.currentThread().getName() + "|" + description + ": " + offset
						+ nextOffset.get());
				break;
			case OnError:
				System.err.println(Thread.currentThread().getName() + "|" + description + ": " + offset
						+ nextOffset.get() + " X " + notification.getThrowable());
				break;
			case OnCompleted:
				System.out.println(
						Thread.currentThread().getName() + "|" + description + ": " + offset + nextOffset.get() + "|");
			default:
				break;
			}
			nextOffset.getAndUpdate(p -> "-" + p);
		};
	}

}