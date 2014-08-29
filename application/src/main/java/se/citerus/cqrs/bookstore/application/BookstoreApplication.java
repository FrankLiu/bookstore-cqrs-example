package se.citerus.cqrs.bookstore.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.api.client.Client;
import io.dropwizard.Application;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.citerus.cqrs.bookstore.admin.web.client.bookcatalog.BookCatalogClient;
import se.citerus.cqrs.bookstore.admin.web.client.order.OrderClient;
import se.citerus.cqrs.bookstore.admin.web.resource.AdminResource;
import se.citerus.cqrs.bookstore.bookcatalog.BookRepository;
import se.citerus.cqrs.bookstore.bookcatalog.BookResource;
import se.citerus.cqrs.bookstore.command.CommandBus;
import se.citerus.cqrs.bookstore.domain.Repository;
import se.citerus.cqrs.bookstore.event.DomainEventBus;
import se.citerus.cqrs.bookstore.event.DomainEventStore;
import se.citerus.cqrs.bookstore.infrastructure.DefaultRepository;
import se.citerus.cqrs.bookstore.infrastructure.GuavaCommandBus;
import se.citerus.cqrs.bookstore.infrastructure.GuavaDomainEventBus;
import se.citerus.cqrs.bookstore.infrastructure.InMemoryDomainEventStore;
import se.citerus.cqrs.bookstore.order.command.OrderCommandHandler;
import se.citerus.cqrs.bookstore.order.publisher.command.PublisherContractCommandHandler;
import se.citerus.cqrs.bookstore.order.saga.PurchaseRegistrationSaga;
import se.citerus.cqrs.bookstore.order.web.OrderCommandResource;
import se.citerus.cqrs.bookstore.order.web.OrderResource;
import se.citerus.cqrs.bookstore.order.web.PublisherResource;
import se.citerus.cqrs.bookstore.query.OrderListDenormalizer;
import se.citerus.cqrs.bookstore.query.OrdersPerDayAggregator;
import se.citerus.cqrs.bookstore.query.QueryService;
import se.citerus.cqrs.bookstore.query.repository.InMemOrderProjectionRepository;
import se.citerus.cqrs.bookstore.shopping.client.bookcatalog.BookClient;
import se.citerus.cqrs.bookstore.shopping.infrastructure.InMemoryCartRepository;
import se.citerus.cqrs.bookstore.shopping.model.CartRepository;
import se.citerus.cqrs.bookstore.shopping.resource.CartResource;

import java.net.URISyntaxException;

import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;

public class BookstoreApplication extends Application<BookstoreConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  @Override
  public void initialize(Bootstrap<BookstoreConfiguration> bootstrap) {
    bootstrap.addBundle(new AssetsBundle("/assets/", "/", "index.html"));
  }

  @Override
  public String getName() {
    return "cqrs-bookstore";
  }

  @Override
  public void run(BookstoreConfiguration bookstoreConfiguration, Environment environment) throws URISyntaxException {

    logger.info("Starting cqrs-bookstore server...");
    logger.info("Creating/registering denormalizers");

    environment.jersey().setUrlPattern("/service/*");

    ObjectMapper objectMapper = environment.getObjectMapper();
    objectMapper.enable(INDENT_OUTPUT);
    objectMapper.enable(WRITE_DATES_AS_TIMESTAMPS);

    CartRepository cartRepository = new InMemoryCartRepository();
    DomainEventBus domainEventBus = new GuavaDomainEventBus();
    InMemOrderProjectionRepository orderRepository = new InMemOrderProjectionRepository();
    OrderListDenormalizer orderListDenormalizer = domainEventBus.register(new OrderListDenormalizer(orderRepository));
    OrdersPerDayAggregator ordersPerDayAggregator = domainEventBus.register(new OrdersPerDayAggregator());

    se.citerus.cqrs.bookstore.query.BookCatalogClient bookCatalogClient = se.citerus.cqrs.bookstore.query.BookCatalogClient.create(Client.create());
    QueryService queryService = new QueryService(orderListDenormalizer, ordersPerDayAggregator, bookCatalogClient);

    DomainEventStore domainEventStore = new InMemoryDomainEventStore();
    Repository aggregateRepository = new DefaultRepository(domainEventBus, domainEventStore);

    CommandBus commandBus = GuavaCommandBus.asyncGuavaCommandBus();
    commandBus.register(new OrderCommandHandler(aggregateRepository, queryService));
    commandBus.register(new PublisherContractCommandHandler(aggregateRepository));

    // Create and register Sagas
    PurchaseRegistrationSaga purchaseRegistrationSaga = new PurchaseRegistrationSaga(queryService, commandBus);
    domainEventBus.register(purchaseRegistrationSaga);

    BookCatalogClient bookCatalogClient1 = BookCatalogClient.create(Client.create());
    BookClient bookClient = BookClient.create(Client.create());
    OrderClient orderClient = OrderClient.create(Client.create());

    environment.jersey().register(new OrderCommandResource(commandBus));
    environment.jersey().register(new BookResource(new BookRepository()));
    environment.jersey().register(new CartResource(bookClient, cartRepository));
    environment.jersey().register(new AdminResource(bookCatalogClient1, orderClient));
    environment.jersey().register(new PublisherResource(commandBus));
    environment.jersey().register(new OrderResource(queryService, domainEventStore));
    logger.info("Server started!");
  }

  public static void main(String[] args) throws Exception {
    new BookstoreApplication().run(args);
  }
}
