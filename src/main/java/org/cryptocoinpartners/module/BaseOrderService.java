package org.cryptocoinpartners.module;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.inject.Inject;
import javax.persistence.Transient;

import org.apache.commons.lang.NotImplementedException;
import org.cryptocoinpartners.enumeration.ExecutionInstruction;
import org.cryptocoinpartners.enumeration.FillType;
import org.cryptocoinpartners.enumeration.OrderState;
import org.cryptocoinpartners.enumeration.PositionEffect;
import org.cryptocoinpartners.enumeration.PositionType;
import org.cryptocoinpartners.enumeration.TransactionType;
import org.cryptocoinpartners.esper.annotation.When;
import org.cryptocoinpartners.exceptions.OrderNotFoundException;
import org.cryptocoinpartners.exceptions.TradingDisabledException;
import org.cryptocoinpartners.schema.Amount;
import org.cryptocoinpartners.schema.Asset;
import org.cryptocoinpartners.schema.Book;
import org.cryptocoinpartners.schema.BookFactory;
import org.cryptocoinpartners.schema.DecimalAmount;
import org.cryptocoinpartners.schema.DiscreteAmount;
import org.cryptocoinpartners.schema.EntityBase;
import org.cryptocoinpartners.schema.Event;
import org.cryptocoinpartners.schema.Exchange;
import org.cryptocoinpartners.schema.Fill;
import org.cryptocoinpartners.schema.FillFactory;
import org.cryptocoinpartners.schema.GeneralOrder;
import org.cryptocoinpartners.schema.GeneralOrderFactory;
import org.cryptocoinpartners.schema.Listing;
import org.cryptocoinpartners.schema.Market;
import org.cryptocoinpartners.schema.Offer;
import org.cryptocoinpartners.schema.Order;
import org.cryptocoinpartners.schema.OrderUpdate;
import org.cryptocoinpartners.schema.OrderUpdateFactory;
import org.cryptocoinpartners.schema.Portfolio;
import org.cryptocoinpartners.schema.Position;
import org.cryptocoinpartners.schema.PositionUpdate;
import org.cryptocoinpartners.schema.SpecificOrder;
import org.cryptocoinpartners.schema.SpecificOrderFactory;
import org.cryptocoinpartners.schema.Trade;
import org.cryptocoinpartners.schema.Tradeable;
import org.cryptocoinpartners.schema.Transaction;
import org.cryptocoinpartners.schema.TransactionFactory;
import org.cryptocoinpartners.service.OrderService;
import org.cryptocoinpartners.service.PortfolioService;
import org.cryptocoinpartners.service.QuoteService;
import org.cryptocoinpartners.util.ConfigUtil;
import org.cryptocoinpartners.util.EM;
import org.cryptocoinpartners.util.Remainder;
import org.cryptocoinpartners.util.RemainderHandler;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;

/**
 * This depends on a QuoteService being attached to the Context first.
 * 
 * @author Tim Olson
 */

//TODO syncronized on order objects, not on this class to improve performance
@SuppressWarnings("UnusedDeclaration")
public abstract class BaseOrderService implements OrderService {
  static {
    service = Executors.newFixedThreadPool(1);

  }

  // ConcurrentHashMap<Listing, ConcurrentHashMap<TransactionType, ConcurrentLinkedQueue<Position>>> listingPosition = new ConcurrentHashMap<Listing, ConcurrentHashMap<TransactionType, ConcurrentLinkedQueue<Position>>>();
  // protected final Lock replacingOrderLock = new ReentrantLock();
  private final Lock triggerOrderLock = new ReentrantLock();
  private final Lock trailingTriggerOrdersLock = new ReentrantLock();

  private transient ConcurrentHashMap<Asset, ConcurrentHashMap<Exchange, ConcurrentHashMap<Listing, ConcurrentHashMap<TransactionType, ConcurrentLinkedQueue<Position>>>>> positionsMap;
  private final int updateOrderAfter = 2;

  private final static Map<Tradeable, ConcurrentHashMap<Double, ConcurrentHashMap<TransactionType, List<Order>>>> triggerOrders = new ConcurrentHashMap<Tradeable, ConcurrentHashMap<Double, ConcurrentHashMap<TransactionType, List<Order>>>>();
  private final static Map<Tradeable, ConcurrentHashMap<Double, ConcurrentHashMap<TransactionType, List<Order>>>> trailingTriggerOrders = new ConcurrentHashMap<Tradeable, ConcurrentHashMap<Double, ConcurrentHashMap<TransactionType, List<Order>>>>();

  protected static boolean cancelUnknownOrders = (ConfigUtil.combined() != null) ? ConfigUtil.combined().getBoolean("cancel.unknownorders", false)
      : false;
  protected static boolean ignoreUnknownOrders = (ConfigUtil.combined() != null) ? ConfigUtil.combined().getBoolean("ignore.unknownorders", true)
      : true;

  @Override
  public void init() {
    for (Portfolio portfolio : portfolioService.getPortfolios())
      findTriggerOrders(portfolio);

  }

  @Override
  public void lockTriggerOrders() {
    // triggerOrderLock.lock();

    // log.debug(this.getClass().getSimpleName() + " : lockTriggerOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);

  }

  @Override
  public void unlockTriggerOrders() {
    //  triggerOrderLock.unlock();

    // log.debug(this.getClass().getSimpleName() + " : unlockTriggerOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);

  }

  // @Override
  // public CountDownLatch getFillProcessingLatch() {
  //     return fillProcessingLatch;

  //}

  @Override
  public synchronized boolean placeOrder(Order order) throws Throwable {
    if (!enableTrading) {
      log.info("Trading Mode Disabled");
      throw new TradingDisabledException("Trading Mode Disabled");
      // return;
    }
    //  order.persit();
    if (order.getVolume() == null || order.getVolume().isZero()) {
      log.info(this.getClass().getSimpleName() + ":placeOrder: Unable to place " + order.getClass().getSimpleName() + ": " + order
          + " as zero volume");
      //   order.persit();

      updateOrderState(order, OrderState.REJECTED, true);
      return false;

      //   updateOrderState(order, OrderState.NEW, true);

    }
    synchronized (order) {

      //   updateOrderState(order, OrderState.NEW, true);
      if (order instanceof GeneralOrder) {
        GeneralOrder generalOrder = (GeneralOrder) order;
        //    generalOrder.persit();
        log.info("new general order recieved " + generalOrder);

        updateOrderState(generalOrder, OrderState.NEW, true);

        handleGeneralOrder(generalOrder);
        // return true;

      } else if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        //    specificOrder.persit();
        log.info("new specific order recieved " + specificOrder);
        // If we are trading a cash market, then the fees as incorprated into the quanity, so we want to add the fee to the quanitty.
        if (specificOrder.getMarket().getListing().getPrompt() == null) {
          long grossAmountCount = specificOrder.getVolume().plus(specificOrder.getForcastedCommission().abs())
              .toBasis(specificOrder.getMarket().getVolumeBasis(), Remainder.ROUND_UP).getCount();
          specificOrder.setVolumeCount(grossAmountCount);
        }

        if (specificOrder.getExecutionInstruction() == null || !specificOrder.getExecutionInstruction().equals(ExecutionInstruction.MANUAL)) {

          try {
            // specificOrder.persit();
            // updateOrderState(specificOrder, OrderState.NEW, true);
            //

            if (specificOrder.getLimitPriceCount() == 0) {
              log.info("new specific order recieved without limit price" + specificOrder);
              Collection<SpecificOrder> pendingOrders = (specificOrder.isBid()) ? getPendingLongOrders() : getPendingShortOrders();
              Amount workingVolume = specificOrder.getUnfilledVolume();
              for (SpecificOrder workingOrder : pendingOrders)
                workingVolume = workingVolume.plus(workingOrder.getUnfilledVolume());
              // if I am buying, then I can buy at current best ask and sell at current best bid
              Book lastBook = quotes.getLastBook(specificOrder.getMarket());
              log.info("BasedOrderSerivce - PlaceOrder: Setting limit prices for market " + specificOrder.getMarket() + " using lastBook" + lastBook);

              //this is not correct, if I am buy at market i will pay the bid price, I I am sell I will pay the ask price
              Offer bestOffer = (specificOrder.isBid()) ? lastBook.getBestAskByVolume(new DiscreteAmount(DiscreteAmount.roundedCountForBasis(
                  workingVolume.asBigDecimal(), specificOrder.getMarket().getVolumeBasis()), specificOrder.getMarket().getVolumeBasis())) : lastBook
                  .getBestBidByVolume(new DiscreteAmount(DiscreteAmount.roundedCountForBasis(workingVolume.asBigDecimal(), specificOrder.getMarket()
                      .getVolumeBasis()), specificOrder.getMarket().getVolumeBasis()));

              // this is short exit, so I am buy, so hitting the ask
              // loop down asks until the total quanity of the order is reached.
              DiscreteAmount limitPrice;
              if (bestOffer != null && bestOffer.getPrice() != null && bestOffer.getPrice().getCount() != 0) {
                limitPrice = bestOffer.getPrice();
                log.info("placeOrder: setting limit price to best offer by volume" + limitPrice + " for order " + specificOrder);
              } else {
                limitPrice = (specificOrder.isBid() ? quotes.getLastBidForMarket(specificOrder.getMarket()).getPrice() : quotes.getLastAskForMarket(
                    specificOrder.getMarket()).getPrice());

                log.info("placeOrder: setting limit price to best offer  " + limitPrice + " for order " + specificOrder);

              }
              limitPrice = (limitPrice == null) ? (specificOrder.isBid()) ? quotes.getLastBidForMarket(specificOrder.getMarket()).getPrice() : quotes
                  .getLastAskForMarket(specificOrder.getMarket()).getPrice() : limitPrice;

              if (limitPrice == null) {
                updateOrderState(specificOrder, OrderState.REJECTED, true);

                log.info("placeOrder: specific order " + specificOrder + " not placed as no prices on book for limit price");
                return false;
              }
              specificOrder.withLimitPrice(limitPrice);
              log.info("placeOrder: setting fill type to market for order " + specificOrder);
              specificOrder.withFillType(FillType.MARKET);
            }
            //   specificOrder.merge();
            handleSpecificOrder(specificOrder);

            //specificOrder.persit();
          } catch (Throwable ex) {
            updateOrderState(specificOrder, OrderState.REJECTED, true);
            throw ex;
          }
        } else if (specificOrder.getExecutionInstruction().equals(ExecutionInstruction.MANUAL)) {
          specificOrder.persit();
          updateOrderState(order, OrderState.FILLED, true);

        }
      }
    }
    // updateOrderState(order, OrderState.NEW, true);

    // order.persit();
    //persitOrderFill(order);
    CreateTransaction(order, true);
    return true;

  }

  private void findTriggerOrders(Portfolio portfolio) {
    //TODO this loads a lot of workking orders why? THink the query is wrong and it returns all orders.
    List<OrderState> openOrderStates = new ArrayList<OrderState>();
    openOrderStates.add(OrderState.TRIGGER);
    openOrderStates.add(OrderState.PARTFILLED);
    // openOrderStates.add(OrderState.NEW);
    openOrderStates.add(OrderState.PLACED);
    openOrderStates.add(OrderState.CANCELLING);
    openOrderStates.add(OrderState.ROUTED);
    HashSet<OrderUpdate> workingOrderUpdates = Sets.newHashSet(EM.namedQueryList(OrderUpdate.class, "orderUpdate.findOrdersByState", openOrderStates,
        portfolio));
    workingOrderUpdates.removeAll(Collections.singleton(null));

    Map<Order, Order> portfolioOrders = new HashMap<Order, Order>();
    //Something wrong in the getAllOrder not retruing all orders loaded in the orderTree. it is only loadign childer orders of fills in positios
    for (Order order : portfolio.getAllOrders()) {
      log.debug("adding order: " + order.getId() + " to portfolio orders");
      if (order.getMarket() != null)
        order.setMarket((Market) portfolio.addMarket(order.getMarket()));

      portfolioOrders.put(order, order);
    }
    Set<Order> workingOrders = new HashSet<Order>();

    for (OrderUpdate orderUpdate : workingOrderUpdates) {
      log.debug("adding order: " + orderUpdate.getOrder().getId() + "/" + System.identityHashCode(orderUpdate.getOrder()) + " to working orders");
      if (orderUpdate.getOrder().getMarket() != null)
        orderUpdate.getOrder().setMarket((Market) portfolio.addMarket(orderUpdate.getOrder().getMarket()));

      workingOrders.add(orderUpdate.getOrder());
    }

    Map<Fill, Fill> portfolioFills = new HashMap<Fill, Fill>();
    for (Fill fill : portfolio.getAllFills()) {
      if (fill.getMarket() != null)
        fill.setMarket((Market) portfolio.addMarket(fill.getMarket()));

      if (fill.getOrder().getMarket() != null)
        fill.getOrder().setMarket((Market) portfolio.addMarket(fill.getOrder().getMarket()));

      portfolioFills.put(fill, fill);
    }
    //all working orders not in porfolio
    SetView<Order> missingPortfolioOrders = Sets.difference(workingOrders, portfolioOrders.keySet());
    for (Order missingOrder : missingPortfolioOrders)
      log.debug("Missing Portfolio Order:" + missingOrder.getId() + "/" + System.identityHashCode(missingOrder));
    // all portfoilo orders that are not in working orders
    SetView<Order> missingWorkingOrders = Sets.difference(portfolioOrders.keySet(), workingOrders);
    for (Order missingOrder : missingWorkingOrders)
      log.debug("Missing Working Order:" + missingOrder.getId() + "/" + System.identityHashCode(missingOrder));

    //all order in both portoflio and working orders
    SetView<Order> portfolioWorkingOrders = Sets.intersection(portfolioOrders.keySet(), workingOrders);
    //log.debug("Missing Working Order:" + portfolioWorkingOrders);

    for (Order missingPortfolioOrder : missingPortfolioOrders) {
      if (portfolioOrders.get(missingPortfolioOrder) == null) {

        log.debug("Loading all child order for missing portfolio " + missingPortfolioOrder.getClass().getSimpleName() + " :"
            + missingPortfolioOrder.getId() + "/" + System.identityHashCode(missingPortfolioOrder));

        // loading all the child order for missing order
        missingPortfolioOrder.loadAllChildOrdersByParentOrder(missingPortfolioOrder, portfolioOrders, portfolioFills);
        if (!portfolioOrders.containsKey(missingPortfolioOrder))
          portfolioOrders.put(missingPortfolioOrder, missingPortfolioOrder);
        // load all the child order for the parent fill
        if (missingPortfolioOrder.getParentFill() != null) {
          log.debug("Loading all child order for parent fill " + missingPortfolioOrder.getParentFill().getId() + "/"
              + System.identityHashCode(missingPortfolioOrder.getParentFill()) + " for missing portfolio "
              + missingPortfolioOrder.getClass().getSimpleName() + " :" + missingPortfolioOrder.getId() + "/"
              + System.identityHashCode(missingPortfolioOrder));

          //   "Portfolio: findOrCreate Loading all child order for fill order " + fill.getOrder().getId());

          missingPortfolioOrder.getParentFill().loadAllChildOrdersByFill(missingPortfolioOrder.getParentFill(), portfolioOrders, portfolioFills);
          log.debug("Loading all child order for parent fill order " + missingPortfolioOrder.getParentFill().getOrder().getId() + "/"
              + System.identityHashCode(missingPortfolioOrder.getParentFill().getOrder()) + " for missing portfolio "
              + missingPortfolioOrder.getClass().getSimpleName() + " :" + missingPortfolioOrder.getId() + "/"
              + System.identityHashCode(missingPortfolioOrder));

          missingPortfolioOrder.getParentFill().getOrder()
              .loadAllChildOrdersByParentOrder(missingPortfolioOrder.getParentFill().getOrder(), portfolioOrders, portfolioFills);
        }

      }

    }
    Set<OrderUpdate> missingOrderUpdate = new HashSet<OrderUpdate>();
    for (Order missingOrder : missingWorkingOrders) {
      List<OrderUpdate> orderUpdates = EM.namedQueryList(OrderUpdate.class, "orderUpdate.findStateByOrder", missingOrder);
      orderUpdates.removeAll(Collections.singleton(null));
      if (orderUpdates == null || orderUpdates.isEmpty()) {
        log.debug("No order updates found for " + missingOrder.getClass().getSimpleName() + " id:" + missingOrder.getId()
            + ". Creating and persisting order update");

        OrderUpdate createdOrderUpdate = orderUpdateFactory.create(missingOrder.getTime(), missingOrder, OrderState.NEW, OrderState.NEW);
        createdOrderUpdate.publishedAt(missingOrder.getTime());
        createdOrderUpdate.persit();
        missingOrderUpdate.add(createdOrderUpdate);
      }

      else {
        for (OrderUpdate orderUpdate : orderUpdates)
          if (orderUpdate.getOrder().equals(missingOrder))
            orderUpdate.setOrder(missingOrder);
        missingOrderUpdate.addAll(orderUpdates);
      }
    }

    workingOrderUpdates.addAll(missingOrderUpdate);

    for (OrderUpdate orderUpdate : workingOrderUpdates) {
      boolean orderInPortfolio = false;
      for (Order portfolioOrder : portfolioOrders.keySet()) {
        if (orderUpdate.getOrder().equals(portfolioOrder)) {
          orderUpdate.setOrder(portfolioOrders.get(portfolioOrder));
          orderInPortfolio = true;
          break;
        }
      }
      if (orderUpdate.getOrder().getPortfolio().equals(portfolio))

        orderUpdate.getOrder().setPortfolio(portfolio);

      log.debug("Adding " + orderUpdate.getOrder().getId() + "/" + System.identityHashCode(orderUpdate.getOrder()) + " with state "
          + orderUpdate.getState() + " to orderStateMap");
      orderStateMap.put(orderUpdate.getOrder(), orderUpdate.getState());
      if (stateOrderMap.get(orderUpdate.getState()) == null) {
        Set<Order> orderSet = new HashSet<Order>();
        stateOrderMap.put(orderUpdate.getState(), orderSet);

      }
      stateOrderMap.get(orderUpdate.getState()).add(orderUpdate.getOrder());
      if (orderUpdate.getState() == (OrderState.TRIGGER))
        addTriggerOrder(orderUpdate.getOrder());

    }
    log.info("loaded state of order: " + workingOrderUpdates);
  }

  @Override
  public Collection<Order> getPendingOrders() {
    //PersitOrderFill(order);
    List<Order> cointraderOpenOrders = new ArrayList<Order>();
    //  for (Order order : orderStateMap.keySet())
    // if (orderStateMap.get(order).isOpen())
    //  cointraderOpenOrders.add(order);
    try {
      //        replacingOrderLock.lock();
      //this == OrderState.NEW || this == OrderState.TRIGGER || this == OrderState.ROUTED || this == OrderState.PLACED || this == OrderState.PARTFILLED;

      if (stateOrderMap.get(OrderState.NEW) != null)
        cointraderOpenOrders.addAll(stateOrderMap.get(OrderState.NEW));
      if (stateOrderMap.get(OrderState.TRIGGER) != null)
        cointraderOpenOrders.addAll(stateOrderMap.get(OrderState.TRIGGER));
      if (stateOrderMap.get(OrderState.PLACED) != null)
        cointraderOpenOrders.addAll(stateOrderMap.get(OrderState.PLACED));
      if (stateOrderMap.get(OrderState.PARTFILLED) != null)
        cointraderOpenOrders.addAll(stateOrderMap.get(OrderState.PARTFILLED));
      if (stateOrderMap.get(OrderState.ROUTED) != null)
        cointraderOpenOrders.addAll(stateOrderMap.get(OrderState.ROUTED));
      if (stateOrderMap.get(OrderState.CANCELLING) != null)
        cointraderOpenOrders.addAll(stateOrderMap.get(OrderState.CANCELLING));
    } catch (Exception e) {
      log.error(this.getClass().getSimpleName() + ": getPendingOrders - Unable to retrive order state ", e);
    } finally {
      // replacingOrderLock.unlock();
    }
    // we need to sort these by time?
    // Collections.sort(cointraderOpenOrders, timeComparator);
    return cointraderOpenOrders;

  }

  public static final Comparator<Order> timeComparator = new Comparator<Order>() {
    // @Override
    //  public int compare(RemoteEvent event, RemoteEvent event2) {
    //    return event.getTime().compareTo(event2.getTime());
    // }
    @Override
    public int compare(Order order, Order order2) {
      // if (event.getRemoteKey() != null && event.getRemoteKey() != null)
      //   return event.getRemoteKey().compareTo(event.getRemoteKey());

      //else
      return (order.getTime().compareTo(order2.getTime()));

    }
  };

  /*
   * @Override public void cancelOrder(Order order) { //PersitOrderFill(order); //CreateTransaction(order); //updateOrderState(order, OrderState); if
   * (orderStateMap.get(order) != null && orderStateMap.get(order).isOpen()) { log.info("cancelOrder: Cancelling  order " + order);
   * updateOrderState(order, OrderState.CANCELLING, true); } else log.info("cancelOrder: Unable to cancel order with state " +
   * orderStateMap.get(order) + " order " + order); }
   * @Override public void updateWorkingOrderQuantity(Order order, Amount quantity) { if (quantity.isZero()) { cancelOrder(order); return; } if (order
   * instanceof GeneralOrder) { GeneralOrder generalOrder = (GeneralOrder) order; log.info("updateing quanity of general order: " + order + " from: "
   * + order.getVolume() + " to: " + quantity); generalOrder.setVolumeDecimal(quantity.asBigDecimal()); generalOrder.getVolume(); } else if (order
   * instanceof SpecificOrder) { SpecificOrder specifcOrder = (SpecificOrder) order; log.info("updateing quanity of specific order: " + order +
   * " from: " + order.getVolume() + " to: " + quantity); handleUpdateSpecificOrderWorkingQuantity(specifcOrder,
   * quantity.toBasis(specifcOrder.getMarket().getVolumeBasis(), Remainder.ROUND_EVEN)); specifcOrder.getVolume(); } //PersitOrderFill(order);
   * //CreateTransaction(order); //updateOrderState(order, OrderState); }
   */
  @Override
  public Collection<Order> handleCancelAllShortStopOrders(Portfolio portfolio, Market market) {
    Collection<Order> cancelledOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return cancelledOrders;

    boolean found = false;
    Fill parentFill;
    Order parentOrder;
    //synchronized (lock) {
    // triggerOrderLock.lock();

    log.debug(this.getClass().getSimpleName() + " : handleCancelAllShortStopOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return cancelledOrders;

    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
        return cancelledOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator(); it.hasNext();) {
          Order triggerOrder = it.next();

          if (triggerOrder.getStopPrice() != null)

            cancelledOrders.add(triggerOrder);
          Collection<SpecificOrder> closingOrders = null;
          if (triggerOrder.getParentFill() != null) {
            triggerOrder.getParentFill().setPositionType(
                (triggerOrder.getParentFill().getVolumeCount() == 0 ? PositionType.FLAT
                    : (triggerOrder.getParentFill().getVolumeCount() > 0 ? PositionType.LONG : PositionType.SHORT)));

            if (triggerOrder.isAsk())
              closingOrders = getPendingLongCloseOrders(triggerOrder.getPortfolio(), triggerOrder.getExecutionInstruction(), triggerOrder.getMarket());
            if (triggerOrder.isBid())
              closingOrders = getPendingShortCloseOrders(triggerOrder.getPortfolio(), triggerOrder.getExecutionInstruction(),
                  triggerOrder.getMarket());

            for (Order closingOrder : closingOrders)
              if (closingOrder.getParentFill() != null && triggerOrder.getParentFill() != null
                  && closingOrder.getParentFill().equals(triggerOrder.getParentFill())) {
                found = true;
                break;
              }

          }
        }
      }

      //    synchronized (triggerOrders.get(parentKey)) {
      if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
          && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null)
        synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).removeAll(cancelledOrders);
        }
      if (triggerOrders != null && triggerOrders.get(market) != null
          && triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null
          && !triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).removeAll(cancelledOrders);
        }
    }
    //   }
    for (Order cancelledOrder : cancelledOrders) {
      updateOrderState(cancelledOrder, OrderState.CANCELLED, true);
      log.info("handleCancelAllShortStopOrders called from class " + Thread.currentThread().getStackTrace()[2]
          + " Cancelled Short Stop Trigger Order: " + cancelledOrder);
    }

    /*
     * if (triggerOrders.get(market).get(TransactionType.BUY).get(parentKey).isEmpty()) {
     * triggerOrders.get(market).get(TransactionType.BUY).remove(parentKey); if (triggerOrders.get(market).get(TransactionType.SELL) != null)
     * triggerOrders.get(market).get(TransactionType.SELL).remove(parentKey); }
     */
    //                if(parentKey instanceof Fill) 
    //                      parentFill = (Fill) parentKey;
    //                if(parentKey instanceof Order) 
    //                     parentOrder = (Order) parentKey;
    //                if ((parentFill!=null && parentFill.getMarket().equals(market)) || (parentOrder!=null && parentOrder.getMarket().equals(market)))
    //                    triggerOrders.remove(parentKey);

    //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill

    return cancelledOrders;
    // triggerOrderLock.unlock();
    // }
  }

  @Override
  public void handleCancelAllTriggerOrdersByParentFill(Fill parentFill) {

    if (parentFill == null)
      return;
    // triggerOrderLock.lock();
    log.debug(this.getClass().getSimpleName() + " : handleCancelAllTriggerOrdersByParentFill to called from stack "
        + Thread.currentThread().getStackTrace()[2]);
    if (triggerOrders.get(parentFill.getMarket()) == null || triggerOrders.get(parentFill.getMarket()).isEmpty())
      return;

    for (Iterator<Double> itd = triggerOrders.get(parentFill.getMarket()).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();

      for (Iterator<TransactionType> ittt = triggerOrders.get(parentFill.getMarket()).get(triggerInterval).keySet().iterator(); ittt.hasNext();) {
        TransactionType transactionType = ittt.next();
        synchronized (triggerOrders.get(parentFill.getMarket()).get(triggerInterval).get(transactionType)) {
          for (Iterator<Order> itto = triggerOrders.get(parentFill.getMarket()).get(triggerInterval).get(transactionType).iterator(); itto.hasNext();) {
            Order triggerOrder = itto.next();

            if (triggerOrder.getParentFill() != null && triggerOrder.getParentFill().equals(parentFill)) {

              itto.remove();
              if (trailingTriggerOrders != null && trailingTriggerOrders.get(parentFill.getMarket()) != null
                  && trailingTriggerOrders.get(parentFill.getMarket()).get(transactionType) != null
                  && !trailingTriggerOrders.get(parentFill.getMarket()).get(transactionType).isEmpty()) {
                synchronized (trailingTriggerOrders.get(parentFill.getMarket()).get(transactionType)) {
                  trailingTriggerOrders.get(parentFill.getMarket()).get(transactionType).remove(triggerOrder);
                }
              }

              log.info("handleCancelAllTriggerOrdersByParentFill called from class " + Thread.currentThread().getStackTrace()[2]
                  + " Cancelled Trigger Order " + triggerOrder + " for : " + parentFill);
            }

          }
        }
      }
    }
  }

  @Override
  public Collection<Order> handleCancelAllLongStopOrders(Portfolio portfolio, Market market) {
    Collection<Order> cancelledOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return cancelledOrders;
    boolean found = false;
    Fill parentFill;
    Order parentOrder;
    //synchronized (lock) {
    // triggerOrderLock.lock();
    log.debug(this.getClass().getSimpleName() + " : handleCancelAllLongStopOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return cancelledOrders;

    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        return cancelledOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator(); it.hasNext();) {
          Order triggerOrder = it.next();

          if (triggerOrder.getStopPrice() != null)

            cancelledOrders.add(triggerOrder);
          Collection<SpecificOrder> closingOrders = null;
          if (triggerOrder.getParentFill() != null) {
            triggerOrder.getParentFill().setPositionType(
                (triggerOrder.getParentFill().getVolumeCount() == 0 ? PositionType.FLAT
                    : (triggerOrder.getParentFill().getVolumeCount() > 0 ? PositionType.LONG : PositionType.SHORT)));

            if (triggerOrder.isAsk())
              closingOrders = getPendingLongCloseOrders(triggerOrder.getPortfolio(), triggerOrder.getExecutionInstruction(), triggerOrder.getMarket());
            if (triggerOrder.isBid())
              closingOrders = getPendingShortCloseOrders(triggerOrder.getPortfolio(), triggerOrder.getExecutionInstruction(),
                  triggerOrder.getMarket());

            for (Order closingOrder : closingOrders)
              if (closingOrder.getParentFill() != null && triggerOrder.getParentFill() != null
                  && closingOrder.getParentFill().equals(triggerOrder.getParentFill())) {
                found = true;
                break;
              }
            //   if (closingOrders == null || closingOrders.isEmpty() || !found)
            //     triggerOrder.getParentFill().setStopPriceCount(0);

          }
        }
      }

      //    synchronized (triggerOrders.get(parentKey)) {
      if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
          && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null)
        synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(cancelledOrders);
        }
      if (triggerOrders != null && triggerOrders.get(market) != null
          && triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null)
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(cancelledOrders);
        }
    }
    //    }
    for (Order cancelledOrder : cancelledOrders) {
      updateOrderState(cancelledOrder, OrderState.CANCELLED, true);
      log.info("handleCancelAllLongStopOrders called from class " + Thread.currentThread().getStackTrace()[2]
          + " Cancelled Long Stop Trigger Order: " + cancelledOrder);

    }

    /*
     * if (triggerOrders.get(market).get(TransactionType.SELL).get(parentKey).isEmpty()) {
     * triggerOrders.get(market).get(TransactionType.SELL).remove(parentKey); if (triggerOrders.get(market).get(TransactionType.BUY) != null)
     * triggerOrders.get(market).get(TransactionType.BUY).remove(parentKey); }
     */

    //                if(parentKey instanceof Fill) 
    //                      parentFill = (Fill) parentKey;
    //                if(parentKey instanceof Order) 
    //                     parentOrder = (Order) parentKey;
    //                if ((parentFill!=null && parentFill.getMarket().equals(market)) || (parentOrder!=null && parentOrder.getMarket().equals(market)))
    //                    triggerOrders.remove(parentKey);

    //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill

    return cancelledOrders;
    // triggerOrderLock.unlock();
    // }
  }

  @Override
  public Collection<Order> handleCancelAllLongStopOrders(Portfolio portfolio, Market market, double interval) {
    Collection<Order> cancelledOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return cancelledOrders;

    boolean found = false;
    Fill parentFill;
    Order parentOrder;
    //synchronized (lock) {
    // triggerOrderLock.lock();
    log.debug(this.getClass().getSimpleName() + " : handleCancelAllLongStopOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return cancelledOrders;

    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        return cancelledOrders;

      //  .get(market).get(TransactionType
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator(); it.hasNext();) {
          Order triggerOrder = it.next();

          if (triggerOrder.getStopPrice() != null && triggerOrder.getOrderGroup() == interval)

            cancelledOrders.add(triggerOrder);
          Collection<SpecificOrder> closingOrders = null;
          if (triggerOrder.getParentFill() != null) {
            triggerOrder.getParentFill().setPositionType(
                (triggerOrder.getParentFill().getVolumeCount() == 0 ? PositionType.FLAT
                    : (triggerOrder.getParentFill().getVolumeCount() > 0 ? PositionType.LONG : PositionType.SHORT)));

            if (triggerOrder.isAsk())
              closingOrders = getPendingLongCloseOrders(triggerOrder.getPortfolio(), triggerOrder.getExecutionInstruction(),
                  triggerOrder.getMarket(), interval);
            if (triggerOrder.isBid())
              closingOrders = getPendingShortCloseOrders(triggerOrder.getPortfolio(), triggerOrder.getExecutionInstruction(),
                  triggerOrder.getMarket(), interval);

            for (Order closingOrder : closingOrders)
              if (closingOrder.getParentFill() != null && triggerOrder.getParentFill() != null
                  && closingOrder.getParentFill().equals(triggerOrder.getParentFill())) {
                found = true;
                break;
              }
            //   if (closingOrders == null || closingOrders.isEmpty() || !found)
            //     triggerOrder.getParentFill().setStopPriceCount(0);

          }
        }
      }

      //    synchronized (triggerOrders.get(parentKey)) {
      //  for (Order cancelledOrder : cancelledOrders)
      //    triggerOrdersTable.get(market).get(TransactionType.SELL).remove(cancelledOrder, cancelledOrder);
      if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
          && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null
          && !trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(cancelledOrders);
        }
      if (triggerOrders != null && triggerOrders.get(market) != null
          && triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null
          && !triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(cancelledOrders);
        }
    }
    for (Order cancelledOrder : cancelledOrders) {
      updateOrderState(cancelledOrder, OrderState.CANCELLED, true);
      log.info("handleCancelAllLongStopOrders called from class " + Thread.currentThread().getStackTrace()[2]
          + " Cancelled Long Stop Trigger Order: " + cancelledOrder);

    }

    /*
     * if (triggerOrders.get(market).get(TransactionType.SELL).get(parentKey).isEmpty()) {
     * triggerOrders.get(market).get(TransactionType.SELL).remove(parentKey); if (triggerOrders.get(market).get(TransactionType.BUY) != null)
     * triggerOrders.get(market).get(TransactionType.BUY).remove(parentKey); }
     */

    //                if(parentKey instanceof Fill) 
    //                      parentFill = (Fill) parentKey;
    //                if(parentKey instanceof Order) 
    //                     parentOrder = (Order) parentKey;
    //                if ((parentFill!=null && parentFill.getMarket().equals(market)) || (parentOrder!=null && parentOrder.getMarket().equals(market)))
    //                    triggerOrders.remove(parentKey);

    //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill

    return cancelledOrders;
    // triggerOrderLock.unlock();
    // }
  }

  @Override
  public boolean handleCancelGeneralOrder(GeneralOrder order) {
    Collection<Order> cancelledOrders = new ConcurrentLinkedQueue<>();
    boolean found = false;
    //synchronized (lock) {
    // triggerOrderLock.lock();
    log.debug(this.getClass().getSimpleName() + " : handleCancelGeneralOrder to called from stack " + Thread.currentThread().getStackTrace()[2]);

    for (Iterator<Tradeable> itm = triggerOrders.keySet().iterator(); itm.hasNext();) {
      Tradeable market = itm.next();
      for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
        Double triggerInterval = itd.next();

        for (Iterator<TransactionType> ittt = triggerOrders.get(market).get(triggerInterval).keySet().iterator(); ittt.hasNext();) {
          TransactionType transactionType = ittt.next();
          synchronized (triggerOrders.get(market).get(triggerInterval).get(transactionType)) {
            for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(transactionType).iterator(); it.hasNext();) {
              Order triggerOrder = it.next();

              if (triggerOrder.equals(order)) {

                cancelledOrders.add(triggerOrder);
                //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill

              }
            }
          }
          //    synchronized (triggerOrders.get(parentKey)) {
          // for (Order cancelledOrder : cancelledOrders)
          //   triggerOrdersTable.get(market).get(transactionType).remove(cancelledOrder, cancelledOrder);
          if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
              && trailingTriggerOrders.get(market).get(triggerInterval).get(transactionType) != null
              && !trailingTriggerOrders.get(market).get(triggerInterval).get(transactionType).isEmpty())
            synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(transactionType)) {
              trailingTriggerOrders.get(market).get(triggerInterval).get(transactionType).removeAll(cancelledOrders);
            }
          if (triggerOrders != null && triggerOrders.get(market) != null
              && triggerOrders.get(market).get(triggerInterval).get(transactionType) != null
              && !triggerOrders.get(market).get(triggerInterval).get(transactionType).isEmpty())
            synchronized (triggerOrders.get(market).get(triggerInterval).get(transactionType)) {
              triggerOrders.get(market).get(triggerInterval).get(transactionType).removeAll(cancelledOrders);
            }

          //    }
          /*
           * if (triggerOrders.get(market).get(transactionType).isEmpty()) {
           * triggerOrders.get(market).get(transactionType).get(parentKey).remove(parentKey); TransactionType oppositeTransactionType =
           * (transactionType.equals(TransactionType.BUY)) ? TransactionType.SELL : TransactionType.BUY; if
           * (triggerOrders.get(market).get(oppositeTransactionType) != null)
           * triggerOrders.get(market).get(oppositeTransactionType).get(parentKey).remove(parentKey); }
           */
          for (Order cancelledOrder : cancelledOrders) {
            updateOrderState(cancelledOrder, OrderState.CANCELLED, true);
            log.info("handleCancelGeneralOrder called from class " + Thread.currentThread().getStackTrace()[2]
                + " Cancelled General  Trigger Order: " + cancelledOrder);
            // if (cancelledOrder.)
            Collection<SpecificOrder> closingOrders = null;
            if (cancelledOrder.getParentFill() != null) {
              cancelledOrder.getParentFill().setPositionType(
                  (cancelledOrder.getParentFill().getVolumeCount() == 0 ? PositionType.FLAT
                      : (cancelledOrder.getParentFill().getVolumeCount() > 0 ? PositionType.LONG : PositionType.SHORT)));

              if (cancelledOrder.isAsk())
                closingOrders = getPendingLongCloseOrders(cancelledOrder.getPortfolio(), cancelledOrder.getExecutionInstruction(),
                    cancelledOrder.getMarket());
              if (cancelledOrder.isBid())
                closingOrders = getPendingShortCloseOrders(cancelledOrder.getPortfolio(), cancelledOrder.getExecutionInstruction(),
                    cancelledOrder.getMarket());

              for (Order closingOrder : closingOrders)
                if (closingOrder.getParentFill() != null && cancelledOrder.getParentFill() != null
                    && closingOrder.getParentFill().equals(cancelledOrder.getParentFill())) {
                  found = true;
                  break;
                }
              // if (closingOrders == null || closingOrders.isEmpty() || !found)
              // cancelledOrder.getParentFill().setStopPriceCount(0);

            }
            // triggerOrderLock.unlock();
            return true;

            // only set the Stop price to zero is no related orders.
            // if it is  a buy order, set the stop price to the highest of all closing orders for this parent
            // so I could have a close order for this fill in the market or I could have another trigger order for this fill in the market.
            // for(this.get.getPendingClosingOrders().
            // if it is a sell order, set the stp price to lowest of all closing orders fo this parent.
            //cancelledOrder.getParentFill().setStopPriceCount(0);

          } // might not be a trigger or

          //                if(parentKey instanceof Fill) 
          //                      parentFill = (Fill) parentKey;
          //                if(parentKey instanceof Order) 
          //                     parentOrder = (Order) parentKey;
          //                if ((parentFill!=null && parentFill.getMarket().equals(market)) || (parentOrder!=null && parentOrder.getMarket().equals(market)))
          //                    triggerOrders.remove(parentKey);

          //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill

        }
      }

    }

    // triggerOrderLock.unlock();
    return false;
    //  }
    // }
  }

  @Override
  public synchronized void adjustShortStopLoss(Amount price, Amount amount, Boolean force, double orderGroup) {
    log.debug(this.getClass().getSimpleName() + " : adjustShortStopLoss to called from stack " + Thread.currentThread().getStackTrace()[2]);
    for (Iterator<Tradeable> itm = triggerOrders.keySet().iterator(); itm.hasNext();) {
      Tradeable market = itm.next();
      for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
        Double triggerInterval = itd.next();

        if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) == null
            || triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
          continue;
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator(); it.hasNext();) {
            Order triggerOrder = it.next();
            if (triggerOrder.isBid() && triggerOrder.getStopPrice() != null
                && (orderGroup == 0 || triggerOrder.getOrderGroup() == 0 || orderGroup == triggerOrder.getOrderGroup())) {
              log.trace("Determining to adjust stops from trigger order stop price " + triggerOrder.getStopPrice() + " to stop price: "
                  + price.plus(amount.abs()));

              long stopPrice = (force) ? (price.plus(amount.abs()).toBasis(triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN))
                  .getCount() : Math.min(
                  (triggerOrder.getStopPrice().toBasis(triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)).getCount(),
                  (price.plus(amount.abs()).toBasis(triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)).getCount());
              DecimalAmount stopDiscrete = DecimalAmount.of(new DiscreteAmount(stopPrice, triggerOrder.getMarket().getPriceBasis()));
              if (stopDiscrete.compareTo(triggerOrder.getStopPrice()) == 0)
                return;
              log.debug(this.getClass().getSimpleName() + ":adjustShortStopLoss Updsting stop price from " + triggerOrder.getStopPrice() + " to "
                  + stopDiscrete + " for " + triggerOrder);

              if ((amount == null || amount.isZero()) && triggerOrder.getStopAmount() != null || !triggerOrder.getStopAmount().isZero()) {

                DecimalAmount impliedStopAmount = DecimalAmount.of(triggerOrder.getStopAmount()
                    .minus(triggerOrder.getStopPrice().minus(stopDiscrete).abs()).abs());
                triggerOrder.setStopAmount(impliedStopAmount);
              }
              triggerOrder.setStopPrice(stopDiscrete);
              triggerOrder.setStopAdjustmentCount(triggerOrder.getStopAdjustmentCount() + 1);

              if (triggerOrder.getParentFill() != null) {
                triggerOrder.getParentFill().setStopPriceCount(stopPrice);
                //   triggerOrder.getParentFill().merge();
              }
              triggerOrder.merge();

            }

          }
        }
      }
      sortShortStopOrders(market);

    }
  }

  @Override
  public synchronized void adjustShortStopLossByAmount(Amount price, Boolean force, double orderGroup, double scaleFactor) {
    log.debug(this.getClass().getSimpleName() + " : adjustShortStopLossByAmount to called from stack " + Thread.currentThread().getStackTrace()[2]);
    for (Iterator<Tradeable> itm = triggerOrders.keySet().iterator(); itm.hasNext();) {
      Tradeable market = itm.next();
      for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
        Double triggerInterval = itd.next();
        if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) == null
            || triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
          continue;
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator(); it.hasNext();) {
            Order triggerOrder = it.next();
            if (triggerOrder.isBid() && triggerOrder.getStopPrice() != null
                && (orderGroup == 0 || triggerOrder.getOrderGroup() == 0 || orderGroup == triggerOrder.getOrderGroup())) {
              //curent stop is 160, new stop price is 100, so we reducethe stop of 160 by scale factor * (triggerOrder.getStopPrice()-price)
              log.trace("Determining to adjust stops from trigger order stop price " + triggerOrder.getStopPrice() + " to stop price: "
                  + triggerOrder.getStopPrice().minus(((triggerOrder.getStopPrice().minus(price))).times(scaleFactor, Remainder.ROUND_EVEN)));

              long stopPrice = (force) ? (triggerOrder.getStopPrice().minus(
                  ((triggerOrder.getStopPrice().minus(price))).times(scaleFactor, Remainder.ROUND_EVEN)).toBasis(triggerOrder.getMarket()
                  .getPriceBasis(), Remainder.ROUND_EVEN)).getCount() : Math.min((triggerOrder.getStopPrice().toBasis(triggerOrder.getMarket()
                  .getPriceBasis(), Remainder.ROUND_EVEN)).getCount(),
                  (triggerOrder.getStopPrice().minus(((triggerOrder.getStopPrice().minus(price))).times(scaleFactor, Remainder.ROUND_EVEN)).toBasis(
                      triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)).getCount());
              DecimalAmount stopDiscrete = DecimalAmount.of(new DiscreteAmount(stopPrice, triggerOrder.getMarket().getPriceBasis()));
              if (stopDiscrete.compareTo(triggerOrder.getStopPrice()) == 0)
                return;
              log.debug(this.getClass().getSimpleName() + ":adjustShortStopLossByAmount Updsting stop price from " + triggerOrder.getStopPrice()
                  + " to " + stopDiscrete + " for " + triggerOrder);

              DecimalAmount impliedStopAmount = DecimalAmount.of(triggerOrder.getStopAmount()
                  .minus(triggerOrder.getStopPrice().minus(stopDiscrete).abs()).abs());
              triggerOrder.setStopAmount(impliedStopAmount);

              triggerOrder.setStopPrice(stopDiscrete);
              triggerOrder.setStopAdjustmentCount(triggerOrder.getStopAdjustmentCount() + 1);

              if (triggerOrder.getParentFill() != null) {
                triggerOrder.getParentFill().setStopPriceCount(stopPrice);
                //   triggerOrder.getParentFill().merge();
              }
              triggerOrder.merge();

            }

          }
        }
        sortShortStopOrders(market);
      }
    }
  }

  @Override
  public synchronized void adjustLongStopLossByAmount(Amount price, Boolean force, double orderGroup, double scaleFactor) {
    log.debug(this.getClass().getSimpleName() + " : adjustLongStopLossByAmount to called from stack " + Thread.currentThread().getStackTrace()[2]);
    for (Iterator<Tradeable> itm = triggerOrders.keySet().iterator(); itm.hasNext();) {
      Tradeable market = itm.next();
      for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
        Double triggerInterval = itd.next();

        if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) == null
            || triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
          continue;
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator(); it.hasNext();) {
            Order triggerOrder = it.next();
            if (triggerOrder.isAsk() && triggerOrder.getStopPrice() != null
                && (orderGroup == 0 || triggerOrder.getOrderGroup() == 0 || orderGroup == triggerOrder.getOrderGroup())) {

              //curent stop is 160, new stop price is 220, so we raise the 160 by scale factor * (triggerOrder.getStopPrice()-price)
              log.trace("Determining to adjust stops from trigger order stop price " + triggerOrder.getStopPrice() + " to stop price: "
                  + triggerOrder.getStopPrice().plus((price.minus(triggerOrder.getStopPrice())).times(scaleFactor, Remainder.ROUND_EVEN)));
              long stopPrice = (force) ? (triggerOrder.getStopPrice().plus(
                  (price.minus(triggerOrder.getStopPrice())).times(scaleFactor, Remainder.ROUND_EVEN)).toBasis(triggerOrder.getMarket()
                  .getPriceBasis(), Remainder.ROUND_EVEN)).getCount() : Math.max((triggerOrder.getStopPrice().toBasis(triggerOrder.getMarket()
                  .getPriceBasis(), Remainder.ROUND_EVEN)).getCount(),
                  (triggerOrder.getStopPrice().plus((price.minus(triggerOrder.getStopPrice())).times(scaleFactor, Remainder.ROUND_EVEN)).toBasis(
                      triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)).getCount());
              DecimalAmount stopDiscrete = DecimalAmount.of(new DiscreteAmount(stopPrice, triggerOrder.getMarket().getPriceBasis()));
              if (stopDiscrete.compareTo(triggerOrder.getStopPrice()) == 0)
                return;
              log.debug(this.getClass().getSimpleName() + ":adjustLongStopLossByAmount Updsting stop price from " + triggerOrder.getStopPrice()
                  + " to " + stopDiscrete + " for " + triggerOrder);

              DecimalAmount impliedStopAmount = DecimalAmount.of(triggerOrder.getStopAmount()
                  .minus(triggerOrder.getStopPrice().minus(stopDiscrete).abs()).abs());
              triggerOrder.setStopAmount(impliedStopAmount);

              triggerOrder.setStopPrice(stopDiscrete);
              triggerOrder.setStopAdjustmentCount(triggerOrder.getStopAdjustmentCount() + 1);

              if (triggerOrder.getParentFill() != null) {
                triggerOrder.getParentFill().setStopPriceCount(stopPrice);
                //  triggerOrder.getParentFill().merge();
              }
              triggerOrder.merge();
            }
          }
        }
      }
      sortLongStopOrders(market);

    }

  }

  @Override
  public synchronized void adjustLongStopLoss(Amount price, Amount amount, Boolean force, double orderGroup) {
    log.debug(this.getClass().getSimpleName() + " : adjustLongStopLoss to called from stack " + Thread.currentThread().getStackTrace()[2]);
    for (Iterator<Tradeable> itm = triggerOrders.keySet().iterator(); itm.hasNext();) {
      Tradeable market = itm.next();
      for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
        Double triggerInterval = itd.next();

        if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) == null
            || triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
          continue;
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator(); it.hasNext();) {
            Order triggerOrder = it.next();
            if (triggerOrder.isAsk() && triggerOrder.getStopPrice() != null
                && (orderGroup == 0 || triggerOrder.getOrderGroup() == 0 || orderGroup == triggerOrder.getOrderGroup())) {
              log.trace("Determining to adjust stops from trigger order stop price " + triggerOrder.getStopPrice() + " to stop price: "
                  + price.minus(amount.abs()));
              long stopPrice = (force) ? (price.minus(amount.abs()).toBasis(triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN))
                  .getCount() : Math.max(
                  (triggerOrder.getStopPrice().toBasis(triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)).getCount(),
                  (price.minus(amount.abs()).toBasis(triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)).getCount());
              DecimalAmount stopDiscrete = DecimalAmount.of(new DiscreteAmount(stopPrice, triggerOrder.getMarket().getPriceBasis()));
              if (stopDiscrete.compareTo(triggerOrder.getStopPrice()) == 0)
                return;
              log.debug(this.getClass().getSimpleName() + ":adjustLongStopLoss Updsting stop price from " + triggerOrder.getStopPrice() + " to "
                  + stopDiscrete + " for " + triggerOrder);
              if ((amount == null || amount.isZero()) && triggerOrder.getStopAmount() != null || !triggerOrder.getStopAmount().isZero()) {

                DecimalAmount impliedStopAmount = DecimalAmount.of(triggerOrder.getStopAmount()
                    .minus(triggerOrder.getStopPrice().minus(stopDiscrete).abs()).abs());
                triggerOrder.setStopAmount(impliedStopAmount);
              }
              triggerOrder.setStopPrice(stopDiscrete);
              triggerOrder.setStopAdjustmentCount(triggerOrder.getStopAdjustmentCount() + 1);

              if (triggerOrder.getParentFill() != null) {
                triggerOrder.getParentFill().setStopPriceCount(stopPrice);
                //  triggerOrder.getParentFill().merge();
              }
              triggerOrder.merge();
            }
          }
        }
        sortLongStopOrders(market);

      }
    }

  }

  @Override
  public synchronized void adjustLongTargetPrices(Amount price, Amount amount, double orderGroup) {
    log.debug(this.getClass().getSimpleName() + " : adjustLongTargetPrices to called from stack " + Thread.currentThread().getStackTrace()[2]);

    for (Iterator<Tradeable> itm = triggerOrders.keySet().iterator(); itm.hasNext();) {
      Tradeable market = itm.next();
      for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
        Double triggerInterval = itd.next();

        if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) == null
            || triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
          continue;
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator(); it.hasNext();) {

            Order triggerOrder = it.next();
            //TODO: should this be the max for bid and the min for shorts
            if (triggerOrder.isAsk() && (orderGroup == 0 || triggerOrder.getOrderGroup() == 0 || orderGroup == triggerOrder.getOrderGroup())) {
              long targetPrice = Math.max(
                  (triggerOrder.getTargetPrice().toBasis(triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)).getCount(),
                  (price.plus(amount.abs()).toBasis(triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)).getCount());
              DecimalAmount targetDiscrete = DecimalAmount.of(new DiscreteAmount(targetPrice, triggerOrder.getMarket().getPriceBasis()));
              triggerOrder.setTargetPrice(targetDiscrete);
              if (triggerOrder.getParentFill() != null)
                triggerOrder.getParentFill().setTargetPriceCount(targetPrice);
            }
          }
        }
      }

    }

  }

  @Override
  public synchronized void adjustShortTargetPrices(Amount price, Amount amount, double orderGroup) {
    log.debug(this.getClass().getSimpleName() + " : adjustShortTargetPrices to called from stack " + Thread.currentThread().getStackTrace()[2]);
    for (Iterator<Tradeable> itm = triggerOrders.keySet().iterator(); itm.hasNext();) {
      Tradeable market = itm.next();
      for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
        Double triggerInterval = itd.next();

        if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) == null
            || triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
          continue;
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator(); it.hasNext();) {

            Order triggerOrder = it.next();
            //TODO: should this be the max for bid and the min for shorts
            if (triggerOrder.isBid() && (orderGroup == 0 || triggerOrder.getOrderGroup() == 0 || orderGroup == triggerOrder.getOrderGroup())) {
              long targetPrice = Math.min(
                  (triggerOrder.getTargetPrice().toBasis(triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)).getCount(),
                  (price.minus(amount.abs()).toBasis(triggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)).getCount());
              DecimalAmount TargetDiscrete = DecimalAmount.of(new DiscreteAmount(targetPrice, triggerOrder.getMarket().getPriceBasis()));
              triggerOrder.setTargetPrice(TargetDiscrete);
              if (triggerOrder.getParentFill() != null)
                triggerOrder.getParentFill().setTargetPriceCount(targetPrice);
            }
          }
        }

      }
    }

  }

  // loop over our  tigger orders

  @Override
  public OrderState getOrderState(Order o) throws IllegalStateException {
    synchronized (o) {
      OrderState state = orderStateMap.get(o);
      if (state == null) {
        try {
          //  log.debug(this.getClass().getSimpleName() + ":getOrderState - Loading order " + o + " from database")
          // let's check if we can get state from exchange or DB
          OrderState orderState = getOrderStateFromOrderService(o);
          if (orderState != null)
            updateOrderState(o, orderState, true);
          else
            throw new IllegalStateException("Untracked order " + o);

        } catch (Throwable e) {
          throw new IllegalStateException("Untracked order " + o);
        }

      }
      return state;
    }
  }

  public Collection<SpecificOrder> getWorkingOrdersOrderFromStateMap() {
    ArrayList<SpecificOrder> orders = new ArrayList<SpecificOrder>();
    for (Order order : orderStateMap.keySet())
      if (order instanceof SpecificOrder && orderStateMap.get(order).isOpen())
        orders.add((SpecificOrder) order);

    return orders;
  }

  public SpecificOrder getSpecifcOrderFromStateMap(UUID id) {
    boolean found = false;
    for (Order order : orderStateMap.keySet()) {
      if (order.getId().equals(id)) {
        found = true;
        if (order instanceof SpecificOrder)
          return (SpecificOrder) order;
      }

    }
    if (!found)
      throw new IllegalStateException("Untracked order " + id);
    return null;

  }

  @Override
  public boolean getTradingEnabled() {

    return enableTrading;
  }

  @Override
  public void setTradingEnabled(Boolean enableTrading) {
    this.enableTrading = enableTrading;
  }

  // @When("@Priority(8) select * from OrderUpdate")
  public void handleOrderUpdate(OrderUpdate orderUpdate) {
    log.debug(this.getClass().getSimpleName() + " : handleOrderUpdate to called from stack " + Thread.currentThread().getStackTrace()[2]);

    //TOOD somethig is up in here causing the states of stop orders to be changed when they are still resting
    OrderState oldState = orderStateMap.get(orderUpdate.getOrder());
    OrderState orderState = orderUpdate.getState();
    Order order = orderUpdate.getOrder();
    //        if (stateOrderMap.get(orderState) == null) {
    //            Set<Order> orderSet = new HashSet<Order>();
    //            // orderSet.add(order);
    //            if (oldState != null)
    //                stateOrderMap.get(oldState).remove(order);
    //            stateOrderMap.put(orderState, orderSet);
    //
    //        }
    switch (orderState) {
      case NEW:
        // stateOrderMap.get(orderState).add(order);

        // orderStateMap.put(order, orderState);

        if (order.getParentOrder() != null
            && (order.getFillType() != FillType.ONE_CANCELS_OTHER || order.getFillType() != FillType.COMPLETED_CANCELS_OTHER))
          updateParentOrderState(order.getParentOrder(), order, orderState);

        //TODO Order persitantce, keep getting TransientPropertyValueException  errors
        //PersitOrderFill(orderUpdate.getOrder());
        break;
      case TRIGGER:
        //  orderStateMap.put(order, orderState);
        // stateOrderMap.get(orderState).add(order);
        // if all children have same state, set parent state.
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && order.getParentOrder().getOrderChildren().isEmpty()) {
          updateParentOrderState(order.getParentOrder(), order, orderState);
          break;
        }
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && !order.getParentOrder().getOrderChildren().isEmpty())
          for (Order childOrder : order.getParentOrder().getOrderChildren()) {
            OrderState childState = getOrderState(childOrder);
            if (childState != orderState)
              break;

            // if (order.getParentOrder() != null)
            updateParentOrderState(order.getParentOrder(), order, orderState);
            break;
          }
        break;
      case ROUTED:
        //  orderStateMap.put(order, orderState);
        // stateOrderMap.get(orderState).add(order);
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && order.getParentOrder().getOrderChildren().isEmpty()) {
          updateParentOrderState(order.getParentOrder(), order, orderState);
          break;
        }
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && !order.getParentOrder().getOrderChildren().isEmpty())
          for (Order childOrder : order.getParentOrder().getOrderChildren()) {
            OrderState childState = getOrderState(childOrder);
            if (childState != orderState)
              break;

            // if (order.getParentOrder() != null)
            updateParentOrderState(order.getParentOrder(), order, orderState);
            break;
          }
        break;
      case PLACED:
        //   orderStateMap.put(order, orderState);
        //  stateOrderMap.get(orderState).add(order);
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && order.getParentOrder().getOrderChildren().isEmpty()) {
          updateParentOrderState(order.getParentOrder(), order, orderState);
          break;
        }
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && !order.getParentOrder().getOrderChildren().isEmpty())
          for (Order childOrder : order.getParentOrder().getOrderChildren()) {
            //the child order might not be placed yet, so we have no chiild state.

            //  OrderState childState = getOrderState(childOrder);
            //if (childState != orderState)
            //  break;

            // if (order.getParentOrder() != null)
            updateParentOrderState(order.getParentOrder(), order, orderState);
            break;
          }
        break;
      case PARTFILLED:
        //    orderStateMap.put(order, orderState);
        //   stateOrderMap.get(orderState).add(order);
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && order.getParentOrder().getOrderChildren().isEmpty()) {
          updateParentOrderState(order.getParentOrder(), order, orderState);
          break;
        }
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && !order.getParentOrder().getOrderChildren().isEmpty())
          for (Order childOrder : order.getParentOrder().getOrderChildren()) {
            OrderState childState = getOrderState(childOrder);
            if (childState != orderState)
              break;

            // if (order.getParentOrder() != null)
            updateParentOrderState(order.getParentOrder(), order, orderState);
            break;
          }
        break;
      case FILLED:
        // orderStateMap.put(order, orderState);
        // stateOrderMap.get(orderState).add(order);
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && order.getParentOrder().getOrderChildren().isEmpty()) {
          updateParentOrderState(order.getParentOrder(), order, orderState);
          break;
        }
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && !order.getParentOrder().getOrderChildren().isEmpty())
          for (Order childOrder : order.getParentOrder().getOrderChildren()) {
            OrderState childState = getOrderState(childOrder);
            if (childState != orderState)

              break;

            // if (order.getParentOrder() != null)
            updateParentOrderState(order.getParentOrder(), order, orderState);
            break;
          }
        break;
      case CANCELLING:
        if (order instanceof GeneralOrder) {
          GeneralOrder generalOrder = (GeneralOrder) order;
          handleCancelGeneralOrder(generalOrder);
        } else if (order instanceof SpecificOrder) {
          SpecificOrder specificOrder = (SpecificOrder) order;
          try {
            if (handleCancelSpecificOrder(specificOrder))
              log.info("handleOrderUpdate cancelled Specific Order:" + specificOrder);
            else
              log.info("handleOrderUpdate unable to cancel Specific Order:" + specificOrder);
          } catch (OrderNotFoundException onf)

          {
            log.info("Order " + specificOrder + " Not found to cacnel");
          }

        }
        break;
      case CANCELLED:
        if (order.getParentFill() != null) {
          order.getParentFill().setPositionType(
              (order.getParentFill().getVolumeCount() == 0 ? PositionType.FLAT : (order.getParentFill().getVolumeCount() > 0 ? PositionType.LONG
                  : PositionType.SHORT)));
        }
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && order.getParentOrder().getOrderChildren().isEmpty()) {
          updateParentOrderState(order.getParentOrder(), order, orderState);
          break;
        }
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && !order.getParentOrder().getOrderChildren().isEmpty())
          for (Order childOrder : order.getParentOrder().getOrderChildren()) {
            OrderState childState = getOrderState(childOrder);
            if (childState != orderState)
              break;

            // if (order.getParentOrder() != null)
            updateParentOrderState(order.getParentOrder(), order, orderState);
            break;
          }
        break;

      case EXPIRED:
        // orderStateMap.put(order, orderState);
        // stateOrderMap.get(orderState).add(order);
        // ;
        if (order.getParentFill() != null) {
          order.getParentFill().setPositionType(
              (order.getParentFill().getVolumeCount() == 0 ? PositionType.FLAT : (order.getParentFill().getVolumeCount() > 0 ? PositionType.LONG
                  : PositionType.SHORT)));
        }
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && order.getParentOrder().getOrderChildren().isEmpty()) {
          updateParentOrderState(order.getParentOrder(), order, orderState);
          break;
        }
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && !order.getParentOrder().getOrderChildren().isEmpty())
          for (Order childOrder : order.getParentOrder().getOrderChildren()) {
            OrderState childState = getOrderState(childOrder);
            if (childState != orderState)
              break;

            // if (order.getParentOrder() != null)
            updateParentOrderState(order.getParentOrder(), order, orderState);
            break;
          }
        break;
      case REJECTED:
        //   orderStateMap.put(order, orderState);
        //  stateOrderMap.get(orderState).add(order);
        // ;
        if (order.getParentFill() != null) {
          order.getParentFill().setPositionType(
              (order.getParentFill().getVolumeCount() == 0 ? PositionType.FLAT : (order.getParentFill().getVolumeCount() > 0 ? PositionType.LONG
                  : PositionType.SHORT)));
        }
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && order.getParentOrder().getOrderChildren().isEmpty()) {
          updateParentOrderState(order.getParentOrder(), order, orderState);
          break;
        }
        if (order.getParentOrder() != null && order.getParentOrder().getOrderChildren() != null
            && !order.getParentOrder().getOrderChildren().isEmpty())
          for (Order childOrder : order.getParentOrder().getOrderChildren()) {
            if (order.equals(childOrder))
              continue;
            OrderState childState = getOrderState(childOrder);
            if (childState != orderState)
              break;

            // if (order.getParentOrder() != null)
            updateParentOrderState(order.getParentOrder(), order, orderState);
            break;
          }
        break;
    }

  }

  //@When("@Priority(9) select * from Fill")
  @Override
  public void handleFillProcessing(Fill fill) {
    // fillProcessingLatch = new CountDownLatch(1);
    log.debug("BaseOrderService:handleFillProcessing Fill Recieved: " + fill);
    handleFill(fill);
    //    fillProcessingLatch.countDown();
    //  fillProcessingLatch = null;
    // service.submit(new handleFillRunnable(fill));
  }

  public synchronized void handleFill(Fill fill) {
    //  log.debug("BaseOrderService:handleFill Fill Received: " + fill);
    log.debug(this.getClass().getSimpleName() + " : handleFill " + fill + " to called from stack " + Thread.currentThread().getStackTrace()[2]);
    fill.persit();
    // UUID orderID = UUID.fromString(fill.getRemoteKey());
    SpecificOrder order = fill.getOrder();

    // = getSpecifcOrderFromStateMap(orderID);
    // if (order == null)
    //   return;
    log.debug("handleFill: Updating position for fill" + fill);
    try {
      fill.getPortfolio().merge(fill);
    } catch (Throwable t) {
      log.debug("error", t);
      throw t;
    }
    //fill.setOrder(order);
    //order.addFill(fill);
    log.debug("persiting fill" + fill);
    if (fill.getPositionEffect() == PositionEffect.CLOSE)

      log.debug("merging fill" + fill);

    //    fill.merge();
    if (log.isInfoEnabled())
      log.info("Received Fill " + fill);
    OrderState state = orderStateMap.get(order);
    if (state == null) {
      log.warn("Untracked order " + order);
      state = OrderState.PLACED;
    }
    if (state == (OrderState.NEW))
      log.warn("Fill received for Order in NEW state: skipping PLACED state");
    if (state.isOpen()) {
      OrderState newState;
      if (order.isFilled()) {
        PositionType newFillState = ((fill.getVolumeCount() == 0 ? PositionType.FLAT : (fill.getVolumeCount() > 0 ? PositionType.LONG
            : PositionType.SHORT)));

        fill.setPositionType(newFillState);
        if (order.getParentFill() != null && (order.getVolume().compareTo(order.getParentFill().getVolume().negate()) == 0))
          order.getParentFill().setPositionType(PositionType.FLAT);

        //   Collection<Order> triggerOrderByParentFill = null;
        // ArrayList<Order> triggerOrdersToRemove = new ArrayList<Order>();
        //  if (order.getParentFill() != null) {
        //  for (Order childOrder : order.getParentFill().getFillChildOrders())
        //    if (childOrder.getUnfilledVolume().isZero())

        //this is called whilst we are processsing a new trade

        //    handleCancelAllTriggerOrdersByParentFill(order.getParentFill());

        //triggerOrderByParentFill = getTriggerOrderByParentFill(order.getParentFill());
        // removeTriggerOrder(triggerOrder);

        //updateOrderState(triggerOrder, OrderState.EXPIRED, true);

        // }
        // let;s pull any stop order with this fill
        // so 
        // handleCancelAllTriggerOrdersByParentFill(order.getParentFill());
        newState = OrderState.FILLED;
      } else
        newState = OrderState.PARTFILLED;
      updateOrderState(order, newState, true);
    }

    //TODO For some reason when this method is called it is locked, so removed syncronisation lock

    log.debug("handleFill: Updated position for fill " + fill);
    //  log.debug("merging fill" + fill);

    // fill.merge();
    //   portfolio.merge
    // portfolio.modifyPosition(transaction.getFill(), new Authorization("Fill for " + transaction.toString()));

    //  
    //TODO I need to update the stop order with the filled or closed out ammount. Might not be fully closed out, we only cancel the stop order when hte other order is fully filled

    //should have already been persisted by position handler
    // persitOrderFill(fill);

    //PersitOrderFill(order);
    FillType fillType = null;
    List<Order> allChildOrders = new ArrayList<Order>();

    if (order.getParentOrder() != null || (order.getOrderChildren() != null && !order.getOrderChildren().isEmpty())) {
      fillType = order.getFillType();
      if (fillType == null)
        fillType = (order.getParentOrder() != null) ? order.getParentOrder().getFillType() : order.getFillType();

      //if we are filled and we have a stop order, we need to update the quanityt of the stop order to the unfilled quantity.
      //  F

      switch (fillType) {
        case GOOD_TIL_CANCELLED:
          break;
        case GTC_OR_MARGIN_CAP:
          break;
        case CANCEL_REMAINDER:
          break;
        case LIMIT:
          break;
        case TRAILING_STOP_LIMIT:
        case TRAILING_UNREALISED_STOP_LIMIT:
        case STOP_LIMIT:
          break;

        case ONE_CANCELS_OTHER:
          SpecificOrder otherOrder;
          GeneralOrder otherGeneralOrder;
          if (order.getParentOrder() != null)

            for (Order pairOrder : order.getParentOrder().getOrderChildren()) {
              //for (Order pairSpecifcOrder : pairOrder.getChildren()) {

              if (pairOrder instanceof SpecificOrder && !pairOrder.equals(order)) {
                otherOrder = (SpecificOrder) pairOrder;
                if (getOrderState(otherOrder).isOpen())
                  try {
                    if (handleCancelSpecificOrder(otherOrder))
                      log.info("handleFill cancelled Specific otherOrder:" + otherOrder + " parent order: " + order.getParentOrder());
                    else
                      log.info("handleFill ubale to cancel Specific otherOrder:" + otherOrder + " parent order: " + order.getParentOrder());
                  } catch (OrderNotFoundException onfe) {
                    log.info("Order Not Found:" + otherOrder);
                  } catch (Exception | Error e) {
                    log.info("cancel specifc order " + otherOrder + ". Full stack trace", e);
                    throw e;
                  }
              }

              if (pairOrder instanceof GeneralOrder && !pairOrder.equals(order)) {
                otherGeneralOrder = (GeneralOrder) pairOrder;
                if (getOrderState(otherGeneralOrder).isOpen())
                  try {

                    if (handleCancelGeneralOrder(otherGeneralOrder))
                      log.info("handleFill cancelled otherGeneralOrder: " + otherGeneralOrder + " parent order: " + order.getParentOrder());
                    else
                      log.info("handleFill ubale to otherGeneralOrder: " + otherGeneralOrder + " parent order: " + order.getParentOrder());

                  } catch (OrderNotFoundException onfe) {
                    log.info("Order Not Found:" + otherGeneralOrder);
                  } catch (IllegalStateException onfe) {
                    log.info("Order Not Placed:" + otherGeneralOrder);

                  } catch (Exception | Error e) {
                    log.info("cancel general order " + otherGeneralOrder + ". Full stack trace", e);
                    throw e;
                  }
              }
              //}
              if (order.getParentOrder().getParentOrder() != null)
                for (Order childPairOrder : order.getParentOrder().getParentOrder().getOrderChildren()) {
                  // for (Order pairSpecifcOrder : pairOrder.getChildren()) {

                  if (childPairOrder instanceof SpecificOrder && childPairOrder != order) {
                    otherOrder = (SpecificOrder) childPairOrder;
                    if (getOrderState(otherOrder).isOpen())

                      try {

                        if (handleCancelSpecificOrder(otherOrder))
                          log.info("handleFill cancelled specific otherOrder Order: " + otherOrder + " parent order: "
                              + order.getParentOrder().getParentOrder());
                        else
                          log.info("handleFill ubale to cancel specific otherOrder Order: " + otherOrder + " parent order: "
                              + order.getParentOrder().getParentOrder());

                        // handleCancelSpecificOrder(otherOrder);
                      } catch (OrderNotFoundException onfe) {
                        log.info("Order Not Found:" + otherOrder);
                      } catch (Exception | Error e) {
                        log.info("cancel spceific order " + otherOrder + ". Full stack trace", e);
                        throw e;
                      }
                  }
                  if (childPairOrder instanceof GeneralOrder && childPairOrder != order.getParentOrder()) {
                    otherGeneralOrder = (GeneralOrder) childPairOrder;
                    if (getOrderState(childPairOrder).isOpen())

                      try {

                        if (handleCancelGeneralOrder(otherGeneralOrder))
                          log.info("handleFill cancelled general otherGeneralOrder Order: " + otherGeneralOrder + ".");
                        else
                          log.info("handleFill ubale to cancel general otherGeneralOrder Order: " + otherGeneralOrder + ".");

                      } catch (OrderNotFoundException onfe) {
                        log.info("Order Not Found:" + otherGeneralOrder);
                      } catch (Exception | Error e) {
                        log.info("cancel general order " + otherGeneralOrder + ". Full stack trace", e);
                        throw e;
                      }
                  }
                  // }
                }
              break;

            }
          break;
        case COMPLETED_CANCELS_OTHER:
          // GeneralOrder{id=99fc7f08-a48a-4701-8e84-7455e1f2b419, parentOrder=null, parentFill=a4276654-cc17-4b4e-ab05-8a0e93021f05, listing=BTC.USD.THISWEEK, volume=-44, unfilled volume=-44, limitPrice=233.62, comment=Stop Order with Price Target, position effect=CLOSE, type=STOP_LIMIT, execution instruction=MAKER, stop price=233.64, target price=244.46} Stop trade Entered at 233.64
          // needs to be cancelled when this is filled
          // SpecificOrder{ time=2015-02-16 06:22:31,id=7461a1d7-6230-4927-afc1-c4ccfa5498f5,remote key=7461a1d7-6230-4927-afc1-c4ccfa5498f5,parentOrder=99fc7f08-a48a-4701-8e84-7455e1f2b419,parentFill=
          //{Id=a4276654-cc17-4b4e-ab05-8a0e93021f05,time=2015-02-16 06:21:51,PositionType=EXITING,Market=OKCOIN_THISWEEK:BTC.USD.THISWEEK,Price=235.44,Volume=44,Open Volume=44,Position Effect=OPEN,Comment=Long Entry Order,Order=eeff7554-6269-4a15-9c42-3132338bf14a,Parent Fill=}
          //,portfolio=MarketMakerStrategy,market=OKCOIN_THISWEEK:BTC.USD.THISWEEK,unfilled volume=0,volumeCount=-44,limitPriceCount=235.61,PlacementCount=1,Comment=Long Exit with resting stop,Order Type=COMPLETED_CANCELS_OTHER,Position Effect=CLOSE,Execution Instruction=MAKER,averageFillPrice=235.9897727272727272727272727272727}

          order.getParentFill().getAllOrdersByParentFill(allChildOrders);
          for (Order childOrder : allChildOrders)
            if (childOrder != order && childOrder.getFillType() == (FillType.COMPLETED_CANCELS_OTHER))
              if ((getOrderState(childOrder).isOpen()) && order.getUnfilledVolumeCount() == 0)
                if (childOrder instanceof SpecificOrder) {
                  SpecificOrder pairSpecificOrder = (SpecificOrder) childOrder;

                  try {
                    if (handleCancelSpecificOrder(pairSpecificOrder))
                      log.info("handleFill cancelled specific pairSpecificOrder Order: " + pairSpecificOrder);
                    else
                      log.info("handleFill ubale to cancel specific pairSpecificOrder Order: " + pairSpecificOrder);

                  } catch (OrderNotFoundException onfe) {
                    log.info("Order Not Found:" + pairSpecificOrder);
                  } catch (Exception | Error e) {
                    log.info("cancel spceific order " + pairSpecificOrder + ". Full stack trace", e);
                    throw e;
                  }
                }

                else if (childOrder instanceof GeneralOrder) {

                  GeneralOrder pairGeneralOrder = (GeneralOrder) childOrder;

                  try {
                    if (handleCancelGeneralOrder(pairGeneralOrder))
                      log.info("handleFill cancelled specific pairGeneralOrder Order: " + pairGeneralOrder);
                    else
                      log.info("handleFill ubale to cancel specific pairGeneralOrder Order: " + pairGeneralOrder);

                  } catch (OrderNotFoundException onfe) {
                    log.info("Order Not Found:" + pairGeneralOrder);
                  } catch (IllegalStateException onfe) {
                    log.info("Order Not Placed:" + pairGeneralOrder);
                  } catch (Exception | Error e) {
                    log.info("cancel general order " + pairGeneralOrder + ". Full stack trace", e);
                    throw e;
                  }

                }

          break;

        case STOP_LOSS:
          //Place a stop order at the stop price

          break;
        case TRAILING_STOP_LOSS:
          //Place a stop order at the stop price

          break;
        default:
          break;

      }
    }
    // always create teh stops
    //    if (fill.getOrder().getParentOrder().getComment().contains("Reentry"))
    //      log.debug("test1");

    GeneralOrder stopOrder = buildStopLimitOrder(fill);
    GeneralOrder targetOrder = buildReentrantLimitOrder(fill);
    //    stopOrder.persit();
    if (stopOrder != null && order.getPositionEffect() == (PositionEffect.OPEN)) {

      try {
        placeOrder(stopOrder);
        log.info("Placed Stop order " + stopOrder);

        // Now we can link them up
      } catch (Throwable e) {
        log.info("Unable to place Stop order " + stopOrder + ". Full stack trace", e);
        // throw e;
      }

    }
    if (targetOrder != null && order.getPositionEffect() == (PositionEffect.CLOSE)) {

      try {
        placeOrder(targetOrder);
        log.info("Placed Target order " + targetOrder);

        // Now we can link them up
      } catch (Throwable e) {
        log.info("Unable to place Target order " + targetOrder + ". Full stack trace", e);
        // throw e;
      }

    }
    /*
     * TransactionType oppositeSide = (order.getParentFill() != null && order.getParentFill().isLong()) ? TransactionType.SELL : TransactionType.BUY;
     * if ((order.getPositionEffect() == null && order.getTransactionType() == oppositeSide) || order.getPositionEffect() == PositionEffect.CLOSE) {
     * // we know this is a closing trade // need to update any stop orders with new quanity ArrayList<Order> allChildOrders = new ArrayList<Order>();
     * getAllOrdersByParentFill(order.getParentFill(), allChildOrders); for (Order childOrder : allChildOrders) if (childOrder != order) { if
     * ((childOrder.getPositionEffect() == null && childOrder.getTransactionType() == oppositeSide) || childOrder.getPositionEffect() ==
     * PositionEffect.CLOSE) { log.info("updating quanity to : " + order.getUnfilledVolume() + " for  order: " + childOrder);
     * updateOrderQuantity(childOrder, order.getUnfilledVolume()); } } }
     */
    // 

    // fill.merge();
    context.route(fill);
    CreateTransaction(fill, true);

  }

  private Collection<Order> getTriggerOrderByParentFill(Fill parentFill) {

    Collection<Order> triggerOrderByParentFill = new ArrayList<Order>();
    if (parentFill == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(parentFill.getMarket()) == null
        || triggerOrders.get(parentFill.getMarket()).isEmpty())
      return triggerOrderByParentFill;
    for (Iterator<Double> itd = triggerOrders.get(parentFill.getMarket()).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      for (Iterator<TransactionType> ittt = triggerOrders.get(parentFill.getMarket()).get(triggerInterval).keySet().iterator(); ittt.hasNext();) {
        TransactionType transactionType = ittt.next();
        if (triggerOrders.get(parentFill.getMarket()).get(transactionType) != null
            && triggerOrders.get(parentFill.getMarket()).get(transactionType) != null) {
          for (Order triggerOrder : triggerOrders.get(parentFill.getMarket()).get(triggerInterval).get(transactionType)) {
            if (triggerOrder.getParentFill() != null && triggerOrder.getParentFill().equals(parentFill))
              triggerOrderByParentFill.add(triggerOrder);
          }

        }
      }
    }

    return triggerOrderByParentFill;

    //loop over all orders for this market and return them.

  }

  private static final Comparator<Fill> timeOrderIdComparator = new Comparator<Fill>() {
    @Override
    public int compare(Fill event, Fill event2) {
      int sComp = event.getTime().compareTo(event2.getTime());
      if (sComp != 0) {
        return sComp;
      } else {
        return (event.getRemoteKey().compareTo(event2.getRemoteKey()));

      }
    }
  };

  private class handleFillRunnable implements Runnable {
    private final Fill fill;

    // protected Logger log;

    public handleFillRunnable(Fill fill) {
      this.fill = fill;

    }

    @Override
    public void run() {
      handleFill(fill);

    }
  }

  private GeneralOrder buildReentrantLimitOrder(Fill fill) {

    //      GeneralOrder order = generalOrderFactory.create(fill.getOrder().getPortfolio(), this));

    //        OrderBuilder stoporder = new OrderBuilder(fill.getOrder().getPortfolio(), this);

    if ((fill.getPositionEffect() != null && fill.getPositionEffect() != PositionEffect.OPEN) && ((!fill.getUnfilledVolume().isZero()))) {
      Amount targetPrice = null;
      DiscreteAmount targetPriceDiscrete;
      BigDecimal bdTargetPrice;
      BigDecimal bdLimitPrice;
      GeneralOrder targetOrder;
      BigDecimal bdVolume = fill.getVolume().asBigDecimal();
      //   targetPrice = fill.getOrder().getParentOrder().getParentFill().getPrice();

      ArrayList<Fill> parentFills = new ArrayList<Fill>();
      Fill parent = null;
      fill.getAllParentFillsByFill(fill, parentFills);
      Collections.reverse(parentFills);
      Amount targetAmount = null;

      for (Fill parentFill : parentFills) {
        if (parentFill.getPositionEffect().equals(PositionEffect.OPEN)) {
          targetPrice = parentFill.getPrice();
          parent = parentFill;
          break;
        }
      }

      if (targetPrice == null)
        return null;
      targetAmount = targetAmount == null ? targetPrice.minus(fill.getPrice()).abs() : targetAmount;

      targetPriceDiscrete = new DiscreteAmount(DiscreteAmount.roundedCountForBasis(targetPrice.asBigDecimal(), fill.getMarket().getPriceBasis()),
          fill.getMarket().getPriceBasis());
      bdTargetPrice = targetPriceDiscrete.asBigDecimal();
      // String comment = (fill.isLong()? "")

      bdLimitPrice = (fill.getVolume().isNegative()) ? targetPriceDiscrete.increment(2).asBigDecimal() : targetPriceDiscrete.decrement(2)
          .asBigDecimal();

      FillType fillType = null;
      if (parent.getFillChildOrders().get(0).getFillType() != null)
        switch (parent.getFillChildOrders().get(0).getFillType()) {
          case TRAILING_STOP_LOSS:
            break;
          case REENTRANT_STOP_LOSS:
          case REENTRANT_STOP_LIMIT:
            fillType = FillType.REENTRANT_STOP_LIMIT;
            break;
          case REENTRANT_TRAILING_STOP_LOSS:
          case REENTRANT_TRAILING_STOP_LIMIT:
            fillType = FillType.REENTRANT_TRAILING_STOP_LIMIT;
            break;
          case TRAILING_UNREALISED_STOP_LOSS:
            break;

        }
      if (fillType == null)
        return null;
      targetOrder = generalOrderFactory.create(context.getTime(), fill, fill.getMarket(), bdVolume.negate(), fillType);
      String comment = (bdVolume.compareTo(BigDecimal.ZERO) > 0) ? "Long Reentry Order" : "Short Reentry Order";
      //   if (stopOrder.getFillType().equals(FillType.TRAILING_UNREALISED_STOP_LIMIT))
      targetOrder.withLastBestPrice(fill.getPrice().asBigDecimal());

      targetOrder.withComment(comment).withTargetPrice(bdTargetPrice).withLimitPrice(bdLimitPrice).withPositionEffect(PositionEffect.OPEN)
          .withExecutionInstruction(fill.getOrder().getExecutionInstruction()).withTargetAmount(targetAmount.asBigDecimal())
          .withTargetPercentage(parent.getFillChildOrders().get(0).getTargetPercentage())
          .withTriggerInterval(parent.getFillChildOrders().get(0).getTriggerInterval()).withUsePosition(false)
          .withOrderGroup(fill.getOrder().getOrderGroup());

      targetOrder.copyCommonFillProperties(fill);
      fill.setTargetPriceCount(targetPriceDiscrete.getCount());
      //this might cause the order to trigger as stop loss exit order!
      // if (fillType.equals(FillType.REENTRANT_STOP_LIMIT) || fillType.equals(FillType.REENTRANT_TRAILING_STOP_LIMIT)) {
      //   targetOrder.withStopPrice(bdStopPrice).withStopAmount(stopAmount.asBigDecimal())
      //         .withStopPercentage(fill.getOrder().getParentOrder().getStopPercentage());
      //fill.setStopPriceCount(stopPriceDiscrete.getCount());
      // }

      Set<Order> parentFillOrders = new HashSet<Order>();
      fill.getAllSpecificOrdersByParentFill(fill, parentFillOrders);
      for (Order childOrder : parentFillOrders) {
        if (childOrder instanceof SpecificOrder) {
          targetOrder.addChildOrder(childOrder);
          childOrder.setParentOrder(targetOrder);
        }
      }
      return targetOrder;
    }
    return null;
  }

  private GeneralOrder buildStopLimitOrder(Fill fill) {

    //      GeneralOrder order = generalOrderFactory.create(fill.getOrder().getPortfolio(), this));

    //        OrderBuilder stoporder = new OrderBuilder(fill.getOrder().getPortfolio(), this);

    //   fill.getAllOrdersByParentFill(allChildren)
    //    if (fill.getOrder().getParentOrder().getComment().contains("Reentry"))
    //      log.debug("test");
    if ((fill.getPositionEffect() != null && fill.getPositionEffect() != PositionEffect.CLOSE) && (!fill.getUnfilledVolume().isZero())) {
      Amount stopPrice = null;
      DiscreteAmount stopPriceDiscrete;
      BigDecimal bdStopPrice;
      BigDecimal bdLimitPrice;
      GeneralOrder stopOrder;
      BigDecimal bdVolume = fill.getVolume().asBigDecimal();
      ArrayList<Order> parentOrders = new ArrayList<Order>();
      fill.getAllParentOrdersByFill(fill, parentOrders);
      Collections.reverse(parentOrders);
      Order parent = null;
      Amount stopAmount = null;
      for (Order parentOrder : parentOrders) {
        if (parentOrder.getStopAmount() != null) {
          stopAmount = (parentOrder.getStopPercentage() != 0) ? fill.getPrice().times(parentOrder.getStopPercentage(), Remainder.ROUND_EVEN)
              : parentOrder.getStopAmount();
          stopPrice = (fill.getVolume().isPositive()) ? fill.getPrice().minus(stopAmount) : fill.getPrice().plus(stopAmount);
          parent = parentOrder;
          break;
        } else if (parentOrder.getStopPrice() != null) {
          stopPrice = parentOrder.getStopPrice();
          parent = parentOrder;
          break;
        }
      }

      if (stopPrice == null)
        return null;
      stopPriceDiscrete = new DiscreteAmount(DiscreteAmount.roundedCountForBasis(stopPrice.asBigDecimal(), fill.getMarket().getPriceBasis()), fill
          .getMarket().getPriceBasis());
      bdStopPrice = stopPriceDiscrete.asBigDecimal();
      // String comment = (fill.isLong()? "")

      bdLimitPrice = (fill.getVolume().isNegative()) ? stopPriceDiscrete.increment(2).asBigDecimal() : stopPriceDiscrete.decrement(2).asBigDecimal();

      FillType fillType = FillType.STOP_LIMIT;
      if (parent.getFillType() != null)
        switch (parent.getFillType()) {
          case TRAILING_STOP_LOSS:
            fillType = FillType.TRAILING_STOP_LIMIT;
            break;
          case REENTRANT_STOP_LOSS:
          case REENTRANT_STOP_LIMIT:
            fillType = FillType.REENTRANT_STOP_LIMIT;
            break;
          case REENTRANT_TRAILING_STOP_LOSS:
          case REENTRANT_TRAILING_STOP_LIMIT:
            fillType = FillType.REENTRANT_TRAILING_STOP_LIMIT;
            break;
          case TRAILING_UNREALISED_STOP_LOSS:
            fillType = FillType.TRAILING_UNREALISED_STOP_LIMIT;
            break;
          default:
            fillType = FillType.STOP_LIMIT;
            break;
        }

      stopOrder = generalOrderFactory.create(context.getTime(), fill, fill.getMarket(), bdVolume.negate(), fillType);
      String comment = (bdVolume.compareTo(BigDecimal.ZERO) > 0) ? "Long Stop Order" : "Short Stop Order";
      //   if (stopOrder.getFillType().equals(FillType.TRAILING_UNREALISED_STOP_LIMIT))
      stopOrder.withLastBestPrice(fill.getPrice().asBigDecimal());

      stopOrder.withComment(comment).withStopPrice(bdStopPrice).withLimitPrice(bdLimitPrice).withPositionEffect(PositionEffect.CLOSE)
          .withExecutionInstruction(fill.getOrder().getExecutionInstruction()).withStopPercentage(parent.getStopPercentage())
          .withTriggerInterval(parent.getTriggerInterval()).withUsePosition(true).withOrderGroup(fill.getOrder().getOrderGroup());
      if (stopAmount != null)
        stopOrder.withStopAmount(stopAmount.asBigDecimal());
      //       if (fillType.equals(FillType.REENTRANT_STOP_LIMIT) || fillType.equals(FillType.REENTRANT_TRAILING_STOP_LIMIT))
      //         stopOrder.withTargetPrice(fill.getPrice().asBigDecimal());
      stopOrder.copyCommonFillProperties(fill);
      fill.setStopPriceCount(stopPriceDiscrete.getCount());

      Set<Order> parentFillOrders = new HashSet<Order>();
      fill.getAllSpecificOrdersByParentFill(fill, parentFillOrders);
      for (Order childOrder : parentFillOrders) {
        if (childOrder instanceof SpecificOrder) {
          stopOrder.addChildOrder(childOrder);
          childOrder.setParentOrder(stopOrder);
        }
      }
      return stopOrder;
    }
    return null;
  }

  protected void handleGeneralOrder(GeneralOrder generalOrder) {
    Market market;
    SpecificOrder specificOrder;
    // generalOrder.persit();

    if (generalOrder.getMarket() == null) {
      Offer offer = generalOrder.isBid() ? quotes.getBestBidForListing(generalOrder.getListing()) : quotes.getBestAskForListing(generalOrder
          .getListing());
      if (offer == null) {
        log.warn("No offers on the book for " + generalOrder.getListing());
        reject(generalOrder, "No recent book data for " + generalOrder.getListing() + " so GeneralOrder routing is disabled");
        return;
      }
      generalOrder.setMarket((Market) offer.getMarket());
    }
    try {
      switch (generalOrder.getFillType()) {
        case GOOD_TIL_CANCELLED:
          throw new NotImplementedException();
        case GTC_OR_MARGIN_CAP:
          throw new NotImplementedException();
        case CANCEL_REMAINDER:
          throw new NotImplementedException();
        case ONE_CANCELS_OTHER:
          specificOrder = convertGeneralOrderToSpecific(generalOrder, generalOrder.getMarket());
          specificOrder.withParentOrder(generalOrder);
          specificOrder.withParentFill(generalOrder.getParentFill());

          if (specificOrder == null)
            break;
          //  specificOrder.withParentFill(generalOrder.getParentFill());
          updateOrderState(specificOrder, OrderState.NEW, true);
          updateOrderState(generalOrder, OrderState.ROUTED, true);
          log.info("Routing OCO order " + generalOrder + " to " + generalOrder.getMarket().getExchange().getSymbol());
          placeOrder(specificOrder);

          break;
        case COMPLETED_CANCELS_OTHER:
          specificOrder = convertGeneralOrderToSpecific(generalOrder, generalOrder.getMarket());
          specificOrder.withParentOrder(generalOrder);
          specificOrder.withParentFill(generalOrder.getParentFill());

          if (specificOrder == null)
            break;
          //  specificOrder.withParentFill(generalOrder.getParentFill());
          updateOrderState(specificOrder, OrderState.NEW, true);
          updateOrderState(generalOrder, OrderState.ROUTED, true);
          log.info("Routing CCO order " + generalOrder + " to " + generalOrder.getMarket().getExchange().getSymbol());
          placeOrder(specificOrder);

          break;
        case LIMIT:
          specificOrder = convertGeneralOrderToSpecific(generalOrder, generalOrder.getMarket());
          specificOrder.withParentOrder(generalOrder);
          specificOrder.withParentFill(generalOrder.getParentFill());
          if (specificOrder == null)
            break;
          //  specificOrder.withParentFill(generalOrder.getParentFill());
          updateOrderState(specificOrder, OrderState.NEW, true);
          updateOrderState(generalOrder, OrderState.ROUTED, true);
          log.info("Routing Limit order " + generalOrder + " to " + generalOrder.getMarket().getExchange().getSymbol());
          placeOrder(specificOrder);

          break;
        case MARKET:
          //TODO If market order, we set the limit price to the price in the order book that will absorb the quantity, then resubmit the order above best ask and below best bid every two polling cycles
          // get the total open quanity of all buy or sell orders for this market and add it up
          // then set the limit price to the vurren value in the order book.
          specificOrder = convertGeneralOrderToSpecific(generalOrder, generalOrder.getMarket());
          if (specificOrder == null)
            break;

          Collection<SpecificOrder> pendingOrders = (specificOrder.isBid()) ? getPendingLongOrders() : getPendingShortOrders();
          Amount workingVolume = specificOrder.getVolume();
          for (SpecificOrder workingOrder : pendingOrders)
            workingVolume = workingVolume.plus(workingOrder.getVolume());
          // if I am buying, then I can buy at current best ask and sell at current best bid
          Book lastBook = quotes.getLastBook(specificOrder.getMarket());
          log.info("BasedOrderSerivce - handleGeneralOrder: Setting limit prices for market " + specificOrder.getMarket() + " using lastBook"
              + lastBook);

          Offer bestOffer = (specificOrder.isBid()) ? lastBook.getBestAskByVolume(new DiscreteAmount(DiscreteAmount.roundedCountForBasis(
              workingVolume.asBigDecimal(), specificOrder.getMarket().getVolumeBasis()), specificOrder.getMarket().getVolumeBasis())) : lastBook
              .getBestBidByVolume(new DiscreteAmount(DiscreteAmount.roundedCountForBasis(workingVolume.asBigDecimal(), specificOrder.getMarket()
                  .getVolumeBasis()), specificOrder.getMarket().getVolumeBasis()));

          // this is short exit, so I am buy, so hitting the ask
          // loop down asks until the total quanity of the order is reached.
          DiscreteAmount limitPrice = (bestOffer.getPrice() == null) ? (specificOrder.isBid()) ? lastBook.getAskPrice() : lastBook.getBidPrice()
              : bestOffer.getPrice();

          specificOrder.withLimitPrice(limitPrice);
          specificOrder.withFillType(FillType.MARKET);
          specificOrder.withParentFill(generalOrder.getParentFill());

          specificOrder.withParentOrder(generalOrder);
          updateOrderState(specificOrder, OrderState.NEW, true);
          updateOrderState(generalOrder, OrderState.ROUTED, true);

          log.info("Routing market order " + generalOrder + " to " + generalOrder.getMarket().getExchange().getSymbol());
          placeOrder(specificOrder);
          break;
        case STOP_LIMIT:
        case REENTRANT_STOP_LIMIT:
          addTriggerOrder(generalOrder);
          updateOrderState(generalOrder, OrderState.TRIGGER, true);
          if (generalOrder.getStopPrice() != null)
            log.info(generalOrder + " Stop trade Entered at " + generalOrder.getStopPrice());
          if (generalOrder.getTargetPrice() != null)
            log.info("Target trade Entered at " + generalOrder.getTargetPrice());
          break;
        case TRAILING_STOP_LIMIT:
        case REENTRANT_TRAILING_STOP_LIMIT:
        case TRAILING_UNREALISED_STOP_LIMIT:
          addTriggerOrder(generalOrder);
          updateOrderState(generalOrder, OrderState.TRIGGER, true);
          log.info("Trailing Stop trade Entered at " + generalOrder.getStopPrice());
          break;
        case TRAILING_STOP_LOSS:
        case TRAILING_UNREALISED_STOP_LOSS:
        case REENTRANT_TRAILING_STOP_LOSS:
        case STOP_LOSS:
        case REENTRANT_STOP_LOSS:
          if (generalOrder.getTargetPrice() != null) {
            // so we are adding an orde that might or might not have a stop price/ammount.
            addTriggerOrder(generalOrder);
            updateOrderState(generalOrder, OrderState.TRIGGER, true);
            log.info("Trigger Order Entered at " + generalOrder.getTargetPrice());
            break;
          } else {
            specificOrder = convertGeneralOrderToSpecific(generalOrder, generalOrder.getMarket());
            if (specificOrder == null)
              break;
            FillType fillType = generalOrder.getExecutionInstruction().equals(ExecutionInstruction.TAKER) ? FillType.MARKET : FillType.LIMIT;
            specificOrder.withFillType(fillType).withParentOrder(generalOrder).withParentOrder(generalOrder)
                .withParentFill(generalOrder.getParentFill());
            updateOrderState(specificOrder, OrderState.NEW, true);
            updateOrderState(generalOrder, OrderState.ROUTED, true);
            log.info("Routing Stop Loss order " + generalOrder + " to " + generalOrder.getMarket().getExchange().getSymbol());
            if (placeOrder(specificOrder)) {
              generalOrder.setLastBestPriceDecimal(specificOrder.getLimitPrice().asBigDecimal());

            }
            break;
          }
      }
    } catch (Throwable e) {
      updateOrderState(generalOrder, OrderState.REJECTED, true);

      log.info("Unable to place general order  " + generalOrder + ". Full stack trace", e);
    }

  }

  //    @SuppressWarnings("ConstantConditions")
  //    @When("@Priority(9) select * from Trade")
  //    private void handleTrade(Trade t) {
  //
  //    }

  // @When("@Priority(6) select * from Book(Book.bidVolumeAsDouble>0, Book.askVolumeAsDouble<0)")
  //  @When("@Priority(6) select * from LastBookWindow")
  private void handleBook(Book b) {
    log.trace("BasedOrderSerivce: handleBook: Book Recieved: " + b);
    updateRestingOrders(b, 0.0);

    //  service.submit(new handleBookRunnable(b));
  }

  //@When("@Priority(8) select * from LastTradeWindow(market.synthetic=false)")
  @SuppressWarnings("ConstantConditions")
  @When("@Priority(2) select * from LastTradeWindow")
  //@When("@Priority(6) select * from Trade(Trade.volumeCount!=0, Trade.market=Portfolio.getMarket(Trade.market))")
  private void handleTrade(Trade t) {
    log.trace("BasedOrderSerivce: handleTrade: Trade Recieved: " + t);
    updateRestingOrders(t, 0.0);

    // mockOrderService.submit(new updateBookRunnable(t));
  }

  private class handleBookRunnable implements Runnable {
    private final Book book;

    // protected Logger log;

    public handleBookRunnable(Book book) {
      this.book = book;

    }

    @Override
    public void run() {
      updateRestingOrders(book, 0.0);

    }
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public Order triggerOrder(Order triggeredOrder, String comment, Collection<Order> triggeredOrders) {
    Boolean orderFound = false;
    //convert order to specfic order
    double interval = 0;
    log.trace(this.getClass().getSimpleName() + " : triggerOrder to called from stack " + Thread.currentThread().getStackTrace()[2]);
    for (Iterator<Double> itd = triggerOrders.get(triggeredOrder.getMarket()).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      synchronized (triggerOrders.get(triggeredOrder.getMarket()).get(triggerInterval).get(triggeredOrder.getTransactionType())) {
        for (Order order : triggerOrders.get(triggeredOrder.getMarket()).get(triggerInterval).get(triggeredOrder.getTransactionType())) {
          if (order.equals(triggeredOrder)) {
            interval = triggerInterval;
            orderFound = true;
            break;
          }

        }
      }
    }
    synchronized ((triggeredOrder.getMarket() != null && triggerOrders.get(triggeredOrder.getMarket()) != null
        && triggerOrders.get(triggeredOrder.getMarket()).get(interval) != null && triggerOrders.get(triggeredOrder.getMarket()).get(interval)
        .get(triggeredOrder.getTransactionType()) != null) ? triggerOrders.get(triggeredOrder.getMarket()).get(interval)
        .get(triggeredOrder.getTransactionType()) : new Object()) {

      OrderState triggerOrderState = orderStateMap.get(triggeredOrder);
      log.debug(this.getClass().getSimpleName() + " : triggerOrder trigger order " + triggeredOrder.getId() + " with state : " + triggerOrderState);
      if (!triggerOrderState.isOpen())
        return triggeredOrder;
      if (!orderFound || (triggerOrderState == null) || (triggerOrderState != null && !triggerOrderState.equals(OrderState.TRIGGER))) {
        log.debug(this.getClass().getSimpleName() + " : triggerOrder unable to trigger order " + triggeredOrder.getId() + " with state "
            + triggerOrderState + " found state: " + orderFound + " as parent fill position type is exiting or not present in trigger Orders: ");
        return null;
      }

      //  + triggeredOrder.getParentFill().getFillChildOrders());
      //  itTriggeredOrder.remove();
      //   triggerOrders.get(parentKey).remove(triggeredOrder);
      // if (triggerOrders.get(parentKey).isEmpty())
      //   triggeredParents.add(parentKey);

      DiscreteAmount unfilledVolumeDiscrete;
      if (triggeredOrder.getParentFill() != null) {
        if (triggeredOrder.getParentFill().getPositionType().equals(PositionType.EXITING)) {
          log.info("UpdateRestingOrders: unable to trigger order " + triggeredOrder.getId() + " as already eixting parent fill");
          // triggerOrderLock.unlock();
          return null;
        }

        unfilledVolumeDiscrete = (triggeredOrder.getUsePosition()) ? new DiscreteAmount(triggeredOrder.getParentFill().getOpenVolumeCount(),
            triggeredOrder.getMarket().getVolumeBasis()).negate() : triggeredOrder.getUnfilledVolume().toBasis(
            triggeredOrder.getMarket().getVolumeBasis(), Remainder.DISCARD);
      } else {
        unfilledVolumeDiscrete = triggeredOrder.getVolume().toBasis(triggeredOrder.getMarket().getVolumeBasis(), Remainder.DISCARD);
      }

      //: triggeredOrder.getParentFill().getOpenVolume()
      //.negate().toBasis(triggeredOrder.getMarket().getVolumeBasis(), Remainder.DISCARD);
      log.info(this.getClass().getSimpleName() + ":updateRestingOrder - unfilledVolumeDiscrete " + unfilledVolumeDiscrete + " with open volume "
          + (triggeredOrder.getParentFill() == null ? triggeredOrder.getVolume() : triggeredOrder.getParentFill().getOpenVolume())
          + " and unfilled volume " + triggeredOrder.getUnfilledVolume() + " for " + triggeredOrder.getId() + " with child orders "
          + triggeredOrder.getOrderChildren());
      for (Order childOrder : triggeredOrder.getOrderChildren()) {
        if (orderStateMap.get(childOrder) != null && orderStateMap.get(childOrder).isOpen())
          if (!handleCancelOrder(childOrder)) {
            log.info("UpdateRestingOrders: unable to cancell all child order " + childOrder.getId() + " from orders: "
                + triggeredOrder.getOrderChildren() + "for order:" + triggeredOrder.getId());
            // triggerOrderLock.unlock();
            return null;
          }
      }
      SpecificOrder specificOrder = convertGeneralOrderToSpecific((GeneralOrder) triggeredOrder, triggeredOrder.getMarket());

      if (specificOrder == null) {
        log.info("triggered order:" + triggeredOrder.getId() + " not convereted to specific order");
        //  triggerOrders.remove(triggeredOrder);
        triggeredOrders.add(triggeredOrder);

        updateOrderState(triggeredOrder, OrderState.REJECTED, true);
        return triggeredOrder;
      }
      //   specificOrder.persit();
      log.info("triggered order:" + triggeredOrder.getId() + " convereted to specific order " + specificOrder);

      //   DiscreteAmount volume = (triggeredOrder.getUnfilledVolume().compareTo(specificOrder.getVolume()) < 0) ? : specificOrder.getVolume()
      //         .toBasis(specificOrder.getMarket().getVolumeBasis(), Remainder.DISCARD);

      // DiscreteAmount volume = (generalOrder.getParentFill() == null) ? generalOrder.getVolume().toBasis(market.getVolumeBasis(), Remainder.DISCARD)
      //       : generalOrder.getParentFill().getOpenVolume().negate().toBasis(market.getVolumeBasis(), Remainder.DISCARD);
      //
      if (!specificOrder.getVolume()
          .equals(triggeredOrder.getUnfilledVolume().toBasis(specificOrder.getMarket().getVolumeBasis(), Remainder.DISCARD)))
        log.debug("Unfilled Volume: " + triggeredOrder.getId() + " not the same as trigger volume:" + specificOrder.getId());

      specificOrder.setVolumeCount(unfilledVolumeDiscrete.getCount());
      specificOrder.setExecutionInstruction(ExecutionInstruction.TAKER);
      specificOrder.setFillType(FillType.MARKET);
      specificOrder.setLimitPriceCount(0);
      specificOrder.setPositionEffect(triggeredOrder.getPositionEffect());
      specificOrder.setComment(comment);
      specificOrder.persit();
      /*
       * if (triggeredOrder.getPositionEffect() == (PositionEffect.OPEN)) { specificOrder.setPositionEffect(PositionEffect.OPEN);
       * specificOrder.setFillType(FillType.LIMIT); //specificOrder.setFillType(FillType.MARKET); //specificOrder.setLimitPriceCount(0); } else {
       * specificOrder.setPositionEffect(PositionEffect.CLOSE); specificOrder.setFillType(FillType.MARKET); specificOrder.setLimitPriceCount(0); }
       */
      // we need to set the sepecifc order children to any working open orders.
      //TODO: loop over open order by parent fill and link them so we know to cancel them is this get's filled first.
      // specificOrder.addChild(this);
      // this.setParentOrder(specificOrder);
      //specificOrder.setFillType(FillType.MARKET);

      //

      //   updateOrderState(specificOrder, OrderState.NEW, true);

      //TODO need 
      // placeOrder(specificOrder);
      //   OrderState newState = order.isFilled() ? OrderState.FILLED : OrderState.PARTFILLED;
      // updateOrderState(triggeredOrder, OrderState.ROUTED);
      //context.route(new PositionUpdate(null, specificOrder.getMarket(), PositionType.SHORT, PositionType.EXITING));

      if (specificOrder.getVolume() != null && !specificOrder.getVolume().isZero()) {
        // need to cancel any order with same parent fill id
        log.info("Cancelling working orders for parent fill: "
            + (triggeredOrder.getParentFill() != null ? triggeredOrder.getParentFill().getId() : " "));
        try {
          if (!getPendingOrders().isEmpty())
            if (triggeredOrder.getParentFill() != null && !handleCancelSpecificOrderByParentFill(triggeredOrder.getParentFill())) {
              log.info("UpdateRestingOrders: unable to cancell all orders by parent fill:" + triggeredOrder.getParentFill().getId());
              // triggerOrderLock.unlock();
              return triggeredOrder;
            }
          specificOrder.setVolumeCount(unfilledVolumeDiscrete.getCount());
          if (specificOrder.getVolume() == null || specificOrder.getVolume().isZero()) {
            log.info(triggeredOrder
                + " not triggered as zero volume"
                + (triggeredOrder.getParentFill() != null ? "fill open volume" + triggeredOrder.getParentFill().getOpenVolume()
                    : "trigger order volume" + triggeredOrder.getVolume()));
            // triggerOrders.remove(triggeredOrder);
            triggeredOrders.add(triggeredOrder);
            //     itto.remove();
            return triggeredOrder;
          }

          // specificOrder.persit();
          log.info("At " + context.getTime() + " Routing trigger order " + triggeredOrder.getId() + " to "
              + triggeredOrder.getMarket().getExchange().getSymbol());
          //If trigger order is not in state map we don't place triggreed Order

          updateOrderState(specificOrder, OrderState.NEW, true);
          updateOrderState(triggeredOrder, OrderState.ROUTED, true);
          if (placeOrder(specificOrder)) {
            //   specificOrder.persit();
            specificOrder.withParentOrder(triggeredOrder);
            specificOrder.withParentFill(triggeredOrder.getParentFill());

            if (triggeredOrder.getParentFill() != null)
              triggeredOrder.getParentFill().setPositionType(PositionType.EXITING);

          }
          //   triggerOrders.remove(triggeredOrder);
          triggeredOrders.add(triggeredOrder);
          //     itto.remove();

          log.info(triggeredOrder.getId() + " triggered as specificOrder " + specificOrder);
          context
              .publish(new PositionUpdate(null, specificOrder.getMarket(), specificOrder.getOrderGroup(), PositionType.SHORT, PositionType.EXITING));
          return triggeredOrder;
        } catch (OrderNotFoundException e) {
          log.info("order not found");
          updateOrderState(specificOrder, OrderState.REJECTED, true);
        } catch (Throwable e) {
          log.error("Unable to place trigged order " + specificOrder.getId() + " ", e);
          updateOrderState(specificOrder, OrderState.REJECTED, true);

        }
      } else {
        //  specificOrder.persit();

        log.info(triggeredOrder
            + " not triggered as zero volume"
            + (triggeredOrder.getParentFill() != null ? "fill open volume" + triggeredOrder.getParentFill().getOpenVolume() : "trigger order volume"
                + triggeredOrder.getVolume()));
        //   triggerOrders.remove(triggeredOrder);
        triggeredOrders.add(triggeredOrder);
        return triggeredOrder;
        //  itto.remove();
        //   updateOrderState(triggeredOrder, OrderState.EXPIRED, true);

      }
      //  triggerOrders.remove(triggeredOrder);
      return triggeredOrder;
    }
    //i = Math.min(0, i - 1);
    //  triggerOrders.remove(triggerOrder);

  }

  @SuppressWarnings("ConstantConditions")
  private synchronized void updateRestingOrders(Event event, Double triggerInterval) {
    if (!getTradingEnabled()) {
      log.info(this.getClass().getSimpleName() + ":updateRestingOrders Not Updating orders as trading mode disabled");
      return;
    }

    Tradeable market = null;
    List<Offer> asks = new ArrayList<>();
    Book b = null;
    Trade t = null;
    List<Offer> bids = new ArrayList<>();
    if (event instanceof Book) {
      b = (Book) event;
      //  market = b.getMarket();

    }

    if (event instanceof Trade) {
      t = (Trade) event;

      //  market = t.getMarket();
      b = bookFactory.create(new Instant(t.getTimestamp()), t.getMarket());

      //if Trade is a sell then it must have big the ask
      if (!t.getMarket().isSynthetic()) {
        if (t.getVolume().isNegative()) {
          Offer bestBid = new Offer(t.getMarket(), t.getTime(), t.getTimeReceived(), t.getPrice().getCount(), t.getVolume().negate().getCount());
          b.getAsks().add(bestBid);
          Offer bestAsk = new Offer(t.getMarket(), t.getTime(), t.getTimeReceived(), t.getPrice().getCount(), t.getVolume().getCount());
          b.getBids().add(bestAsk);

        } else {
          Offer bestAsk = new Offer(t.getMarket(), t.getTime(), t.getTimeReceived(), t.getPrice().getCount(), t.getVolume().negate().getCount());
          b.getBids().add(bestAsk);
          Offer bestBid = new Offer(t.getMarket(), t.getTime(), t.getTimeReceived(), t.getPrice().getCount(), t.getVolume().getCount());
          b.getAsks().add(bestBid);
        }
      } else {
        if (t.getVolume().isNegative()) {
          Offer bestBid = new Offer(t.getMarket(), t.getTime(), t.getTimeReceived(), t.getPrice().asBigDecimal(), t.getVolume().asBigDecimal()
              .negate());
          b.getAsks().add(bestBid);
          Offer bestAsk = new Offer(t.getMarket(), t.getTime(), t.getTimeReceived(), t.getPrice().asBigDecimal(), t.getVolume().asBigDecimal());
          b.getBids().add(bestAsk);

        } else {
          Offer bestAsk = new Offer(t.getMarket(), t.getTime(), t.getTimeReceived(), t.getPrice().asBigDecimal(), t.getVolume().asBigDecimal()
              .negate());
          b.getBids().add(bestAsk);
          Offer bestBid = new Offer(t.getMarket(), t.getTime(), t.getTimeReceived(), t.getPrice().asBigDecimal(), t.getVolume().asBigDecimal());
          b.getAsks().add(bestBid);
        }
      }

      //     b.build();

    }
    market = b.getMarket();
    asks = b.getAsks();
    bids = b.getBids();
    Offer offer;
    //  synchronized (lock) {
    Offer ask = b.getBestAsk();

    Offer bid = b.getBestBid();

    if (bid == null || bid.getPrice().isZero() || ask == null || ask.getPrice().isZero())
      return;
    log.trace("Bid price for trigger: " + bid.getPrice() + ". Ask price for trigger: " + ask.getPrice());

    //    Iterator<Order> itOrder = getPendingOrders().iterator();
    //  while (itOrder.hasNext()) {
    //    Order order = itOrder.next();
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder && getOrderState(order) != OrderState.NEW) {
        SpecificOrder pendingOrder = (SpecificOrder) order;
        if (pendingOrder.getExpiryTime() != null && context.getTime().isAfter(pendingOrder.getExpiryTime())
            && !pendingOrder.getExecutionInstruction().equals(ExecutionInstruction.MAKERTOTAKER)) {
          synchronized (pendingOrder) {
            try {

              if (getOrderState(pendingOrder) != null && getOrderState(pendingOrder).isOpen()) {
                log.info("Order Expired at " + context.getTime() + " with state " + getOrderState(pendingOrder) + ". Cancelling Specifc Order: "
                    + pendingOrder);
                if (handleCancelSpecificOrder(pendingOrder))
                  log.info("udpateRestingOrders cancelled Specific Order:" + pendingOrder);
                else
                  log.info("udpateRestingOrders ubale to cancel Specific Order:" + pendingOrder);
              }
              //cancelled = false;
            } catch (Exception e) {
              log.error("attmept to cancel an order that was not pending :" + pendingOrder + " " + e);
            }
          }

        } else if ((pendingOrder.getExpiryTime() != null && context.getTime().isAfter(pendingOrder.getExpiryTime()) && pendingOrder
            .getExecutionInstruction().equals(ExecutionInstruction.MAKERTOTAKER))
            || (getOrderState(pendingOrder) != null && getOrderState(pendingOrder).isOpen() && pendingOrder.getMarket().equals(b.getMarket()) && (pendingOrder
                .getFillType() != null && pendingOrder.getFillType().equals(FillType.MARKET)))) {
          int placementCount = Math.min(pendingOrder.getPlacementCount() + 1, updateOrderAfter * 10);
          pendingOrder.setPlacementCount(placementCount);
          //only update on every 2nd tick.
          if ((pendingOrder.getPlacementCount() % updateOrderAfter) != 0)
            continue;
          //prevoius max price was 24^4 ticks => 3317.76, now it is 16^2=2.56 ticks
          // if a sell order, if the limit price<best bid, then cointinue
          Book lastBook = quotes.getLastBook(pendingOrder.getMarket());
          //  market sell order needs to be <=current bit
          //market buy order needs to be >=current ask

          //limit sell order needs to be <current ask
          //limit buy order needs to be >current bid

          //nd ask>bid
          if (pendingOrder.getLimitPrice() != null
              && !pendingOrder.getLimitPrice().isZero()
              && ((pendingOrder.isAsk() && pendingOrder.getLimitPrice().compareTo(lastBook.getBestBid().getPrice()) <= 0) || (pendingOrder.isBid() && pendingOrder
                  .getLimitPrice().compareTo(lastBook.getBestAsk().getPrice()) >= 0)))
            continue;
          pendingOrder.setFillType(FillType.MARKET);
          pendingOrder.setExecutionInstruction(ExecutionInstruction.TAKER);
          //  pendingOrder.setLimitPriceCount(0);

          Collection<SpecificOrder> pendingOrders = (pendingOrder.isBid()) ? getPendingLongOrders() : getPendingShortOrders();
          Amount workingVolume = pendingOrder.getUnfilledVolume();
          for (SpecificOrder workingOrder : pendingOrders)
            workingVolume = workingVolume.plus(workingOrder.getUnfilledVolume());
          // if I am buying, then I can buy at current best ask and sell at current best bid

          log.info("BasedOrderSerivce - UpdateRestingOrdsers: Setting limit prices for market " + pendingOrder.getMarket() + " using lastBook"
              + lastBook);
          Offer bestOffer = (pendingOrder.isBid()) ? lastBook.getBestAskByVolume(new DiscreteAmount(DiscreteAmount.roundedCountForBasis(
              workingVolume.asBigDecimal(), pendingOrder.getMarket().getVolumeBasis()), pendingOrder.getMarket().getVolumeBasis())) : lastBook
              .getBestBidByVolume(new DiscreteAmount(DiscreteAmount.roundedCountForBasis(workingVolume.asBigDecimal(), pendingOrder.getMarket()
                  .getVolumeBasis()), pendingOrder.getMarket().getVolumeBasis()));

          //if it is a buy, I want to buy at highest possible price
          // can only reduce price by maximum of 25% i.e. 
          if (bestOffer.getPriceCount() == 0 || bestOffer.getPriceCount() == Long.MAX_VALUE) {
            log.warn("BasedOrderSerivce - unable to update order book at book is not present lastBook : " + lastBook);
            break;
          }
          DiscreteAmount limitPrice = pendingOrder.getLimitPrice();
          DiscreteAmount updatedlimitPrice = pendingOrder.getLimitPrice();
          // 4 

          //   if(Math.)
          // if ((Math.pow(pendingOrder.getPlacementCount(), 2) * 4) < lastBook.getBidPrice().getBasis()) {
          // so if I am selling I am sellling bid

          // so we are going to decrements by this number of ticks?
          int index = (pendingOrder.isBid()) ? Math.min(lastBook.getAsks().size() - 1, pendingOrder.getPlacementCount()) : Math.min(lastBook
              .getBids().size() - 1, pendingOrder.getPlacementCount());
          updatedlimitPrice = (pendingOrder.isBid()) ? lastBook.getAsks().get(index).getPrice() : lastBook.getBids().get(index).getPrice();

          //     (((pendingOrder.isAsk())
          //   lastBook.getAsks().
          // Amount priceUpdate = bestOffer.getPrice().times((pendingOrder.getPlacementCount() / (double) (updateOrderAfter * 200)),
          //       Remainder.ROUND_EVEN);

          //updatedlimitPrice = (((pendingOrder.isAsk()) ? bestOffer.getPrice().minus(priceUpdate) : bestOffer.getPrice().plus(priceUpdate))).toBasis(
          //      pendingOrder.getMarket().getPriceBasis(), Remainder.ROUND_FLOOR);
          //Sell at lowest possible price
          limitPrice = (limitPrice == null) ? updatedlimitPrice : limitPrice;

          // } else {
          //   updatedlimitPrice = (pendingOrder.isAsk()) ? bestOffer.getPrice() : bestOffer.getPrice();
          // pendingOrder.setPlacementCount(1);
          // }
          //Sell at lowest possible price
          if (pendingOrder.isAsk()) {
            if (updatedlimitPrice.isPositive() && limitPrice.compareTo(updatedlimitPrice) > 0)
              limitPrice = updatedlimitPrice;
            else {
              pendingOrder.setPlacementCount(Math.min(pendingOrder.getPlacementCount() - 1, updateOrderAfter * 10));

              return;
            }
          } else {
            if (limitPrice == null) {
              log.debug("pendingOrder:" + pendingOrder);
              log.debug("pendingOrderState:" + orderStateMap.get(order));
              log.debug("pendingOrderState:" + stateOrderMap.get(orderStateMap.get(order)));
            }

            if (updatedlimitPrice.isPositive() && limitPrice.compareTo(updatedlimitPrice) < 0)
              limitPrice = updatedlimitPrice;
            else {
              pendingOrder.setPlacementCount(Math.min(pendingOrder.getPlacementCount() - 1, updateOrderAfter * 10));

              return;
            }
          }
          // limitPrice = (limitPrice.compareTo(updatedlimitPrice) > 0 ? limitPrice : updatedlimitPrice);
          // what about if 
          //TODO  we only need to do this is the best bid/best ask has changed vs previous
          log.debug("replacing existing market order with fill type " + pendingOrder.getFillType() + " expirty time " + pendingOrder.getExpiryTime()
              + ":" + pendingOrder);
          SpecificOrder replaceOrder = specificOrderFactory.create(pendingOrder);

          try {
            if (getOrderState(pendingOrder).isOpen()) {

              //   replacingOrderLock.lock();
              // let's place the new order first!

              replaceOrder.setPlacementCount(pendingOrder.getPlacementCount());
              replaceOrder.withLimitPrice(limitPrice);
              replaceOrder.setVolumeCount(pendingOrder.getUnfilledVolume().getCount());
              //}
              log.debug("placing replacment market order :" + replaceOrder + " for order" + pendingOrder);

              if (placeOrder(replaceOrder)) {
                log.debug("updateRestingOrders:Replaced market order with state : " + getOrderState(pendingOrder) + " " + pendingOrder + " with "
                    + replaceOrder);
                log.info("Canceling  Orders " + pendingOrder + " with state " + getOrderState(pendingOrder));
                if (handleCancelSpecificOrder(pendingOrder))
                  log.info("cancelled  Orders " + pendingOrder + " with state " + getOrderState(pendingOrder));
                else
                  handleCancelSpecificOrder(replaceOrder);
              }
            }
          } catch (Throwable e) {
            log.error("updateRestingOrders: Unable to place replacment old order  " + pendingOrder + " with state " + getOrderState(pendingOrder)
                + " with replacement order " + replaceOrder + " with state " + getOrderState(replaceOrder), e);
          }
          //cancelled = false;
          /*
           * // if (cancelled) { // if (getOrderState(pendingOrder).isCancelled()) { // OrderBuilder.SpecificOrderBuilder builder = new
           * OrderBuilder(pendingOrder.getPortfolio()).create(pendingOrder); // order.setLimitPriceCount(limitPrice.getCount()); //
           * placementCount=Math.min(pendingOrder.getPlacementCount(), b) //DiscreteAmount volume; //if (pendingOrder.getParentOrder() != null) { //
           * volume = (pendingOrder.getParentOrder().getUnfilledVolume().compareTo(pendingOrder.getUnfilledVolume()) < 0) ? pendingOrder //
           * .getParentOrder().getUnfilledVolume().toBasis(pendingOrder.getMarket().getVolumeBasis(), Remainder.DISCARD) // :
           * pendingOrder.getVolume().toBasis(pendingOrder.getMarket().getVolumeBasis(), Remainder.DISCARD); // DiscreteAmount volume =
           * (generalOrder.getParentFill() == null) ? generalOrder.getVolume().toBasis(market.getVolumeBasis(), Remainder.DISCARD) // :
           * generalOrder.getParentFill().getOpenVolume().negate().toBasis(market.getVolumeBasis(), Remainder.DISCARD); // so we might have a delay in
           * fills so we need try { if (placeOrder(replaceOrder)) { log.debug("updateRestingOrders:Replaced market order with state : " +
           * getOrderState(pendingOrder) + " " + pendingOrder + " with " + replaceOrder); } else { SpecificOrder orginalOrder =
           * specificOrderFactory.create(pendingOrder); orginalOrder.setPlacementCount(1); orginalOrder.withLimitPrice(pendingOrder.getLimitPrice());
           * orginalOrder.setVolumeCount(pendingOrder.getUnfilledVolume().getCount());
           * log.error("updateRestingOrders: Unable to place replacment order " + replaceOrder + " Submitting orginal order " + orginalOrder); try {
           * placeOrder(orginalOrder); log.debug("updateRestingOrders:Submitting orginal order:" + orginalOrder); } catch (Throwable ex) {
           * log.debug("updateRestingOrders:Unable to submit orginal order:" + orginalOrder + ex); } } } try { placeOrder(orginalOrder);
           * log.debug("updateRestingOrders:Submitting orginal order:" + orginalOrder); } catch (Throwable ex) {
           * log.debug("updateRestingOrders:Unable to submit orginal order:" + orginalOrder + ex); } } } else
           * log.debug("updateRestingOrders: Unable to replace market order: " + pendingOrder + " with state: " + getOrderState(pendingOrder)); //
           * log.info("submitted new order :" + replaceOrder); } else { log.debug("updateRestingOrders: Unable to cancel market order: " +
           * pendingOrder + " with state: " + getOrderState(pendingOrder)); pendingOrder.setPlacementCount(1); } } } catch (Throwable e) {
           * log.error("updateRestingOrders: attmept to cancel an order that was not pending :" + pendingOrder + " " + e); } finally { //
           * replacingOrderLock.unlock(); }
           */

        }

      }
      // for (SpecificOrder pendingOrder : getPendingOrders()) {

      /*
       * if (pendingOrder.getMarket().equals(b.getMarket()) && //pendingOrder.getParentOrder().getFillType() == FillType.STOP_LIMIT)
       * (pendingOrder.getPositionEffect() == PositionEffect.CLOSE) && (pendingOrder.getUnfilledVolumeCount() != 0) &&
       * pendingOrder.getExecutionInstruction() == ExecutionInstruction.TAKER) { boolean cancelled = true;
       * pendingOrder.setPlacementCount(pendingOrder.getPlacementCount() + 1); if (pendingOrder.isAsk()) offer =
       * (pendingOrder.getExecutionInstruction() == ExecutionInstruction.MAKER) ? quotes.getLastAskForMarket(pendingOrder.getMarket()) :
       * quotes.getLastBidForMarket(pendingOrder.getMarket()); else offer = (pendingOrder.getExecutionInstruction() == ExecutionInstruction.MAKER) ?
       * quotes.getLastBidForMarket(pendingOrder.getMarket()) : quotes.getLastAskForMarket(pendingOrder.getMarket()); DiscreteAmount limitPrice =
       * (pendingOrder.getVolume().isNegative()) ? bid.getPrice().decrement( (long) (Math.pow(pendingOrder.getPlacementCount(), 4))) :
       * ask.getPrice().increment( (long) (Math.pow(pendingOrder.getPlacementCount(), 4))); // we neeed to cancle order //TODO surround with try catch
       * so we only insert if we cancel //TODO we only need to do this is the best bid/best ask has changed vs previous
       * log.debug("canceling existing order :" + pendingOrder); try { if (!getOrderState(pendingOrder).isCancelled()) {
       * log.info("Canceling Closing Orders " + pendingOrder); handleCancelSpecificOrder(pendingOrder); //cancelled = false; } // if (cancelled) { //
       * OrderBuilder.SpecificOrderBuilder builder = new OrderBuilder(pendingOrder.getPortfolio()).create(pendingOrder); SpecificOrder order =
       * specificOrderFactory.create(pendingOrder); // order.setLimitPriceCount(limitPrice.getCount());
       * order.setPlacementCount(pendingOrder.getPlacementCount()); DiscreteAmount volume; if (pendingOrder.getParentOrder() != null) { volume =
       * (pendingOrder.getParentOrder().getUnfilledVolume().compareTo(pendingOrder.getVolume()) < 0) ? pendingOrder.getParentOrder()
       * .getUnfilledVolume().toBasis(pendingOrder.getMarket().getVolumeBasis(), Remainder.DISCARD) : pendingOrder.getVolume().toBasis(
       * pendingOrder.getMarket().getVolumeBasis(), Remainder.DISCARD); // DiscreteAmount volume = (generalOrder.getParentFill() == null) ?
       * generalOrder.getVolume().toBasis(market.getVolumeBasis(), Remainder.DISCARD) // :
       * generalOrder.getParentFill().getOpenVolume().negate().toBasis(market.getVolumeBasis(), Remainder.DISCARD);
       * order.setVolumeCount(volume.getCount()); } placeOrder(order); log.info("submitted new order :" + order); // } } catch (Exception e) {
       * log.error("attmept to cancel an order that was not pending :" + pendingOrder + " " + e); } //PersistUtil.merge(pendingOrder); //
       * order.persit(); // PersistUtil.merge(pendingOrder); // pendingOrder.persit(); }
       */
    }
    // }

    // synchronized (lock) {
    /*
     * ArrayList<Event> triggeredParents = new ArrayList<Event>(); for (Event parentKey : triggerOrders.keySet()) { ArrayList<Order> triggeredOrders =
     * new ArrayList<Order>(); for (Order triggeredOrder : triggerOrders.get(parentKey)) { log.trace("determining to trigger order:" +
     * triggeredOrder); if (triggeredOrder.getExpiryTime() != null && context.getTime().isAfter(triggeredOrder.getExpiryTime())) {
     * log.info("Expired Trigger order:" + triggeredOrder); triggeredOrders.add(triggeredOrder); } }
     * triggerOrders.get(parentKey).removeAll(triggeredOrders); if (triggerOrders.get(parentKey).isEmpty()) triggeredParents.add(parentKey); } for
     * (Event parent : triggeredParents) triggerOrders.remove(parent);
     */

    List<Event> triggeredParents = new ArrayList<Event>();
    //   log.debug(this.getClass().getSimpleName() + " : UpdateRestingOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null || triggerOrders.get(market).isEmpty()
        || triggerOrders.get(market).get(triggerInterval) == null || triggerOrders.get(market).get(triggerInterval).isEmpty())
      return;
    // synchronized (triggerOrders) {
    lockTriggerOrders();
    if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null
        && !triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty()) {
      //  for (Event parentKey : triggerOrders.keySet()) {
      //  Iterator<Event> itEvent = triggerOrders.keySet().iterator();
      //while (itEvent.hasNext()) {
      // closing position
      //  Event parentKey = itEvent.next();
      ArrayList<Order> triggeredOrders = new ArrayList<Order>();
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
        Iterator<Order> itto = triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator();
        //  int size = triggerOrders.get(tr).get(triggerInterval).get(TransactionType.BUY).size();
        while (itto.hasNext()) {
          Order triggeredOrder = itto.next();
          //     synchronized (triggeredOrder) {
          // this is a buy order
          //  for (Order triggeredOrder : triggerOrders.get(market).get(TransactionType.BUY).get(parentKey)) {
          /*
           * Iterator<Order> it = triggerOrders.get(parentKey).iterator(); while (it.hasNext()) { Order triggeredOrder = it.next();
           */

          // for (Order triggeredOrder : triggerOrders.get(parentKey)) {

          log.trace("determining to trigger resting buy  order:" + triggeredOrder.getId()
              + (triggeredOrder.getStopPrice() != null ? " with stop price " + (triggeredOrder.getStopPrice()) : "")
              + (triggeredOrder.getTargetPrice() != null ? " target price " + (triggeredOrder.getTargetPrice()) : "") + " for interval "
              + triggerInterval + " at Bid price for trigger: " + bid.getPrice() + ". Ask price for trigger: " + ask.getPrice());

          if (triggeredOrder.getExpiryTime() != null && context.getTime().isAfter(triggeredOrder.getExpiryTime())
          //   && triggerOrders.get(market).get(TransactionType.BUY).contains(triggeredOrder)
          ) {
            log.info("Trigger order Expired, cancelling general Order:" + triggeredOrder);
            // addTriggerOrder(triggeredOrder)

            triggeredOrders.add(triggeredOrder);
            //   itto.remove();
            // triggerOrders.remove(triggeredOrder);

            continue;
          }

          if (b.getMarket() != null) {
            try {
              // if the order is a buy order, then I want to trigger at best price I can sell at (best bid)
              if (triggeredOrder.isBid() && triggeredOrder.getTimestamp() < b.getBestAsk().getTimestamp()
              //     && triggerOrders.get(market).get(TransactionType.BUY).contains(triggeredOrder)
              ) {
                // triggerOrderLock.lock();

                //I want to buy at current ask. entry to can take ask or make bid + increment
                //bids={10.0@606.81;2.0@606.57;12.0@606.45;1.0@605.67;87.0@605.66} asks={-96.0@607.22;-64.0@607.49;-121.0@607.51;-4.0@607.59;-121.0@607.79}

                //so if we are buying, we will have to buy at ask 
                DiscreteAmount triggerPrice = b.getBestAsk().getPrice();

                //quotes.getLastBook(triggeredOrder.getMarket()).getBestAsk().getPrice();

                //  Long triggerPrice = (triggeredOrder.getExecutionInstruction() == (ExecutionInstruction.MAKER)) ? quotes
                //        .getLastBook(triggeredOrder.getMarket()).getBestBid().getPriceCount() : quotes.getLastBook(triggeredOrder.getMarket())
                //      .getBestAsk().getPriceCount();
                // NPE on line 1644

                //    <= //
                //          >=
                // log.trace("BaseOrderService: updateRestingOrders" + triggeredOrder);
                if (triggeredOrder != null
                    && ((triggeredOrder.getStopPrice() != null && (triggerPrice.compareTo(triggeredOrder.getStopPrice()) >= 0)) || (triggeredOrder
                        .getTargetPrice() != null && (triggerPrice.compareTo(triggeredOrder.getTargetPrice()) >= 0)))) {
                  //614.62
                  //615                                    
                  log.info(this.getClass().getSimpleName() + ":updatingRestingOrder - At price " + triggerPrice + " " + event
                      + " triggered order: buy" + triggeredOrder);
                  if (triggeredOrder.getParentFill() != null)
                    log.info("triggered order:"
                        + triggeredOrder
                        + " parent fill unfilled volume:"
                        + (triggeredOrder.getParentFill().getUnfilledVolume() != null ? (triggeredOrder.getParentFill().getUnfilledVolume() + " parent fill position type:")
                            : "")

                        //  triggeredOrder.getParentFill().getUnfilledVolume() + " parent fill position type:"
                        + (triggeredOrder.getParentFill().getPositionType() != null ? (triggeredOrder.getParentFill().getPositionType() + " parent Fill child orders: ")
                            : "")

                        //   + triggeredOrder.getParentFill().getPositionType() + " parent Fill child orders: "
                        + (triggeredOrder.getParentFill().getFillChildOrders() != null ? (triggeredOrder.getParentFill().getFillChildOrders()) : ""));
                  String comment = null;
                  if (triggeredOrder.getStopPrice() != null && (triggerPrice.compareTo(triggeredOrder.getStopPrice()) >= 0)) {
                    //    if (triggeredOrder.getPositionEffect() == null || triggeredOrder.getPositionEffect().equals(PositionEffect.CLOSE))
                    comment = "Short Stop Order with Stop Price";
                    //  else
                    //    comment = "Long Entry Order with Target Price";
                    //specificOrder.setExecutionInstruction(ExecutionInstruction.TAKER);
                  } else
                    comment = "Long Entry Order with Target Price";
                  //        if (triggeredOrder.getPositionEffect() == (PositionEffect.CLOSE))
                  //      comment = "Short Stop Order with Stop Price";
                  //  else if (triggeredOrder.getPositionEffect() == (PositionEffect.OPEN))
                  //    comment = "Long Entry Order with Target Price";
                  // when we call trigger order, it calls handel fill, which cause the trigger orders to be cancelled!
                  if (triggerOrder(triggeredOrder, comment, triggeredOrders) == null) {
                    triggeredOrders.add(triggeredOrder);
                    continue;
                  } else {
                    triggeredOrders.add(triggeredOrder);
                    //itto.remove();
                  }

                } else
                  break;

                // triggerOrderLock.unlock();

              }
            } catch (NullPointerException npe) {
              // most likey thrown cos the trigger order have been removed by a cancel action
              log.info(
                  "BaseOrderService: UpdateRestingOrders - Trigger order removed for triggerOrders hashmap whilst process a market data event, continuing to process remaining trigger orders ",
                  npe);
              continue;

            } catch (Throwable ex) {
              log.info("BaseOrderService: UpdateRestingOrders - Unable to process trigger orders", ex);
              unlockTriggerOrders();
              throw ex;
            }
          }
        }
      }
      //
      //lets' move all the trailing stops
      if (trailingTriggerOrders.get(market) != null && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null
          && !trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty()) {
        boolean updatedOrders = false;
        synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          Iterator<Order> ittto = trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator();
          while (ittto.hasNext()) {
            Order trailingTriggerOrder = ittto.next();
            //    synchronized (trailingTriggerOrder) {
            //  Iterator<Order> ittto = triggerOrdersTable.get(market).get(TransactionType.BUY).columnKeySet().iterator();
            //while (ittto.hasNext()) {
            //  Order trailingTriggerOrder = ittto.next();

            log.trace("determining to to update trailing trigger order:" + trailingTriggerOrder.getId() + " at Bid price for trigger: "
                + bid.getPrice() + ". Ask price for trigger: " + ask.getPrice());

            if (b.getMarket() != null) {
              try {
                // if the order is a buy order, then I want to trigger at best price I can sell at (best bid)
                if (trailingTriggerOrder.getTimestamp() < b.getBestAsk().getTimestamp()) {
                  DiscreteAmount triggerPrice = b.getBestAsk().getPrice();

                  if (trailingTriggerOrder.getFillType() != null
                      && (trailingTriggerOrder.getFillType().equals(FillType.TRAILING_STOP_LIMIT) || trailingTriggerOrder.getFillType().equals(
                          FillType.REENTRANT_TRAILING_STOP_LIMIT))) {
                    log.trace(this.getClass().getSimpleName() + "- updateRestingOrders: Determing if any buy trailing stops to update for order id "
                        + trailingTriggerOrder.getId());
                    if (trailingTriggerOrder.getUnfilledVolume().isZero()) {
                      log.debug(this.getClass().getSimpleName() + "- updateRestingOrders: Removed  buy trailing stop with zero unfilled  "
                          + trailingTriggerOrder);

                      triggeredOrders.add(trailingTriggerOrder);
                      // ittto.remove();

                    }
                    Amount stopAmount = (trailingTriggerOrder.getStopPercentage() != 0) ? triggerPrice.times(
                        trailingTriggerOrder.getStopPercentage(), Remainder.ROUND_EVEN) : trailingTriggerOrder.getStopAmount();
                    // these are buy orders to exit a short, so if price is failing we want to move stop down.
                    // price =100, amount =10, stop price =120, as price + stop amount < current stop price
                    //654.42 + 99.64 > 750.52 
                    if (trailingTriggerOrder.getStopPrice() != null
                        && ((triggerPrice.getCount() + (stopAmount.toBasis(trailingTriggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)
                            .getCount())) < (trailingTriggerOrder.getStopPrice().toBasis(trailingTriggerOrder.getMarket().getPriceBasis(),
                            Remainder.ROUND_EVEN).getCount()))) {

                      long stopPrice = Math.min((trailingTriggerOrder.getStopPrice().toBasis(trailingTriggerOrder.getMarket().getPriceBasis(),
                          Remainder.ROUND_EVEN)).getCount(), (triggerPrice.getCount() + (stopAmount.toBasis(trailingTriggerOrder.getMarket()
                          .getPriceBasis(), Remainder.ROUND_EVEN)).getCount()));
                      DiscreteAmount stopDiscrete = (new DiscreteAmount(stopPrice, trailingTriggerOrder.getMarket().getPriceBasis()));
                      log.debug(this.getClass().getSimpleName() + "- updateRestingOrders: At " + context.getTime()
                          + " updating buy trailing stop from " + trailingTriggerOrder.getStopPrice() + " to " + stopDiscrete + " for order id "
                          + trailingTriggerOrder.getId());

                      trailingTriggerOrder.setStopPrice(DecimalAmount.of(stopDiscrete));
                      trailingTriggerOrder.setStopAdjustmentCount(trailingTriggerOrder.getStopAdjustmentCount() + 1);

                      updatedOrders = true;
                      trailingTriggerOrder.merge();

                      if (trailingTriggerOrder.getParentFill() != null) {
                        trailingTriggerOrder.getParentFill().setStopPriceCount(stopDiscrete.getCount());
                        trailingTriggerOrder.getParentFill().merge();
                      }

                    } else
                      break;

                  }
                }
              } catch (NullPointerException npe) {
                // most likey thrown cos the trigger order have been removed by a cancel action
                log.info("BaseOrderService: UpdateRestingOrders - Trigger order removed for triggerOrders hashmap whilst process a market data event, continuing to process remaining trigger orders");
                continue;

              } catch (Throwable ex) {
                log.info("BaseOrderService: UpdateRestingOrders - Unable to process trigger orders", ex);
                unlockTriggerOrders();
                throw ex;
              }
            }

          }
        }
        if (updatedOrders) {
          sortOrders(triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY), TransactionType.BUY, market,
              ascendingStopPriceComparator);
          sortOrders(trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY), TransactionType.BUY, market,
              descendingTrailingStopPriceComparator);
        }

      }
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null
          && !triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty() && !triggeredOrders.isEmpty()) {
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).removeAll(triggeredOrders);
        }
        if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
            && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null)
          synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
            trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).removeAll(triggeredOrders);
          }
        if (triggerOrders.get(market) != null && triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null
            && triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null) {
          synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
            triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(triggeredOrders);
          }
          if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
              && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null)
            synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
              trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(triggeredOrders);
            }
        }
      }

    }
    if (triggerOrders.get(market) != null && triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null
        && !triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty()) {

      //  for (Event parentKey : triggerOrders.keySet()) {
      //  Iterator<Event> itEvent = triggerOrders.keySet().iterator();
      //while (itEvent.hasNext()) {
      // closing position
      //  Event parentKey = itEvent.next();
      ArrayList<Order> triggeredOrders = new ArrayList<Order>();
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
        Iterator<Order> itto = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator();

        while (itto.hasNext()) {
          Order triggeredOrder = itto.next();
          //  synchronized (triggeredOrder) {
          //  Event parentKey = itps.next();
          //for (Order triggeredOrder : triggerOrders.get(market).get(TransactionType.SELL).get(parentKey)) {
          /*
           * Iterator<Order> it = triggerOrders.get(parentKey).iterator(); while (it.hasNext()) { Order triggeredOrder = it.next();
           */

          // for (Order triggeredOrder : triggerOrders.get(parentKey)) {
          log.trace("determining to trigger resting sell  order:" + triggeredOrder.getId()
              + (triggeredOrder.getStopPrice() != null ? " with stop price " + (triggeredOrder.getStopPrice()) : "")
              + (triggeredOrder.getTargetPrice() != null ? " target price " + (triggeredOrder.getTargetPrice()) : "") + " for interval "
              + triggerInterval + " at Bid price for trigger: " + bid.getPrice() + ". Ask price for trigger: " + ask.getPrice());

          if (triggeredOrder.getExpiryTime() != null && context.getTime().isAfter(triggeredOrder.getExpiryTime())) {

            log.info("Trigger order Expired, cancelling general Order:" + triggeredOrder);
            // addTriggerOrder(triggeredOrder)
            //  triggerOrders.remove(triggeredOrder);
            triggeredOrders.add(triggeredOrder);
            //  itto.remove();
            //  itto.remove();
            //    triggeredOrders.add(triggeredOrder);
            continue;

          }
          if (b.getMarket() != null) {
            try {

              if (triggeredOrder.getTimestamp() < b.getBestBid().getTimestamp()) {
                // triggerOrderLock.lock();
                //  I want to sell at market bid price, as long, so when the 
                //buy entry to can take ask or make bid + increment
                //bids={10.0@606.81;2.0@606.57;12.0@606.45;1.0@605.67;87.0@605.66} asks={-96.0@607.22;-64.0@607.49;-121.0@607.51;-4.0@607.59;-121.0@607.79}
                // so sell order, I need to sell at the current bid
                DiscreteAmount triggerPrice = b.getBestBid().getPrice();

                /*
                 * && if ((triggeredOrder != null) && ((triggeredOrder.getStopPrice() != null &&
                 * (!(triggerPrice.compareTo(triggeredOrder.getStopPrice()) > 0))) || (triggeredOrder .getTargetPrice() != null &&
                 * (((triggeredOrder.getPositionEffect() == null || triggeredOrder .getPositionEffect() == (PositionEffect.CLOSE)) &&
                 * (!(triggerPrice.compareTo(triggeredOrder.getTargetPrice()) < 0))) || (triggeredOrder .getPositionEffect() == (PositionEffect.OPEN)
                 * && (!(triggerPrice.compareTo(triggeredOrder.getTargetPrice()) > 0))))))) {
                 */
                if (triggeredOrder != null
                    && ((triggeredOrder.getStopPrice() != null && (triggerPrice.compareTo(triggeredOrder.getStopPrice()) <= 0)) || (triggeredOrder
                        .getTargetPrice() != null && (triggerPrice.compareTo(triggeredOrder.getTargetPrice()) <= 0)))) {

                  log.info(this.getClass().getSimpleName() + ":updatingRestingOrder - At price " + triggerPrice + " " + event
                      + " triggered sell order:" + triggeredOrder);

                  if (triggeredOrder.getParentFill() != null)
                    log.info("triggered order:"
                        + triggeredOrder
                        + " parent fill unfilled volume:"
                        + (triggeredOrder.getParentFill().getUnfilledVolume() != null ? (triggeredOrder.getParentFill().getUnfilledVolume() + " parent fill position type:")
                            : "")

                        //  triggeredOrder.getParentFill().getUnfilledVolume() + " parent fill position type:"
                        + (triggeredOrder.getParentFill().getPositionType() != null ? (triggeredOrder.getParentFill().getPositionType() + " parent Fill child orders: ")
                            : "")

                        //   + triggeredOrder.getParentFill().getPositionType() + " parent Fill child orders: "
                        + (triggeredOrder.getParentFill().getFillChildOrders() != null ? (triggeredOrder.getParentFill().getFillChildOrders()) : ""));
                  String comment = null;
                  if (triggeredOrder.getStopPrice() != null && (triggerPrice.compareTo(triggeredOrder.getStopPrice()) <= 0)) {
                    //  if (triggeredOrder.getPositionEffect() == null || triggeredOrder.getPositionEffect().equals(PositionEffect.CLOSE))
                    comment = "Long Stop Order with Stop Price";
                    //      else
                    //        comment = "Short Entry Order with Target Price";

                  } else
                    comment = ("Short Entry Order with Target Price");
                  //   if (triggeredOrder.getPositionEffect() == (PositionEffect.CLOSE))
                  // comment = ("Long Stop Order with Stop Price");
                  //else if (triggeredOrder.getPositionEffect() == (PositionEffect.OPEN))
                  //  comment = ("Short Entry Order with Target Price");

                  if (triggerOrder(triggeredOrder, comment, triggeredOrders) == null) {
                    triggeredOrders.add(triggeredOrder);

                    continue;
                  } else {
                    triggeredOrders.add(triggeredOrder);
                    //    itto.remove();
                  }
                  // itTriggeredOrder.remove();
                  //  triggerOrders.get(parentKey).remove(triggeredOrder);

                  //   triggerOrders.remove(parentKey);

                  // portfolioService.publishPositionUpdate(new Position(triggeredOrder.getPortfolio(), triggeredOrder.getMarket().getExchange(), triggeredOrder.getMarket(), triggeredOrder.getMarket().getBase(),
                  //       DecimalAmount.ZERO, DecimalAmount.ZERO));

                  //i = Math.min(0, i - 1);
                  //triggeredOrders.add(triggeredOrder);

                } else
                  break;
              }
            } catch (NullPointerException npe) {
              // most likey thrown cos the trigger order have been removed by a cancel action
              log.info("BaseOrderService: UpdateRestingOrders - Trigger order removed for triggerOrders hashmap whilst process a market data event, continuing to process remaining trigger orders");
              continue;

            } catch (Throwable ex) {
              log.info("BaseOrderService: UpdateRestingOrders - Unable to process trigger orders", ex);
              unlockTriggerOrders();
              throw ex;
            }
          }
        }
      }

      if (trailingTriggerOrders.get(market) != null && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null
          && !trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty()) {
        boolean updatedOrders = false;
        synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          Iterator<Order> ittto = trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator();
          while (ittto.hasNext()) {
            Order trailingTriggerOrder = ittto.next();
            //  synchronized (trailingTriggerOrder) {
            //  for (Order trailingTriggerOrder : trailingTriggerOrders.get(market).get(TransactionType.SELL)) {

            //    Iterator<Order> ittto = triggerOrdersTable.get(market).get(TransactionType.SELL).columnKeySet().iterator();
            //  while (ittto.hasNext()) {
            //    Order trailingTriggerOrder = ittto.next();

            log.trace("determining to to update trailing trigger order:" + trailingTriggerOrder.getId() + " at Bid price for trigger: "
                + bid.getPrice() + ". Ask price for trigger: " + ask.getPrice());

            if (b.getMarket() != null) {
              try {
                // if the order is a buy order, then I want to trigger at best price I can sell at (best bid)
                if (trailingTriggerOrder.getTimestamp() < b.getBestAsk().getTimestamp()) {
                  DiscreteAmount triggerPrice = b.getBestAsk().getPrice();

                  if (trailingTriggerOrder.getFillType() != null
                      && (trailingTriggerOrder.getFillType().equals(FillType.TRAILING_STOP_LIMIT) || trailingTriggerOrder.getFillType().equals(
                          FillType.REENTRANT_TRAILING_STOP_LIMIT))) {
                    log.trace(this.getClass().getSimpleName() + "- updateRestingOrders: Determining if sell trailing stops to update for order id "
                        + trailingTriggerOrder.getId());
                    if (trailingTriggerOrder.getUnfilledVolume().isZero()) {
                      log.debug(this.getClass().getSimpleName() + "- updateRestingOrders: Removed  buy trailing stop with zero unfilled  "
                          + trailingTriggerOrder);

                      triggeredOrders.add(trailingTriggerOrder);
                      //  ittto.remove();
                    }
                    Amount stopAmount = (trailingTriggerOrder.getStopPercentage() != 0) ? triggerPrice.times(
                        trailingTriggerOrder.getStopPercentage(), Remainder.ROUND_EVEN) : trailingTriggerOrder.getStopAmount();

                    //////

                    if (trailingTriggerOrder.getStopPrice() != null
                        && ((triggerPrice.getCount() - (stopAmount.toBasis(trailingTriggerOrder.getMarket().getPriceBasis(), Remainder.ROUND_EVEN)
                            .getCount())) > (trailingTriggerOrder.getStopPrice().toBasis(trailingTriggerOrder.getMarket().getPriceBasis(),
                            Remainder.ROUND_EVEN).getCount()))) {

                      long stopPrice = Math.max((trailingTriggerOrder.getStopPrice().toBasis(trailingTriggerOrder.getMarket().getPriceBasis(),
                          Remainder.ROUND_EVEN)).getCount(), (triggerPrice.getCount() - (stopAmount.toBasis(trailingTriggerOrder.getMarket()
                          .getPriceBasis(), Remainder.ROUND_EVEN)).getCount()));
                      DiscreteAmount stopDiscrete = (new DiscreteAmount(stopPrice, trailingTriggerOrder.getMarket().getPriceBasis()));
                      log.debug(this.getClass().getSimpleName() + "- updateRestingOrders: At " + context.getTime()
                          + " updating sell trailing stop from " + trailingTriggerOrder.getStopPrice() + " to " + stopDiscrete + " for order id "
                          + trailingTriggerOrder.getId());

                      trailingTriggerOrder.setStopPrice(DecimalAmount.of(stopDiscrete));
                      trailingTriggerOrder.setStopAdjustmentCount(trailingTriggerOrder.getStopAdjustmentCount() + 1);
                      updatedOrders = true;

                      trailingTriggerOrder.merge();
                      if (trailingTriggerOrder.getParentFill() != null) {
                        trailingTriggerOrder.getParentFill().setStopPriceCount(stopDiscrete.getCount());
                        trailingTriggerOrder.getParentFill().merge();
                      }

                    } else
                      break;

                  }
                }
              } catch (NullPointerException npe) {
                // most likey thrown cos the trigger order have been removed by a cancel action
                log.info("BaseOrderService: UpdateRestingOrders - Trigger order removed for triggerOrders hashmap whilst process a market data event, continuing to process remaining trigger orders");
                continue;

              } catch (Throwable ex) {
                log.info("BaseOrderService: UpdateRestingOrders - Unable to process trigger orders", ex);
                unlockTriggerOrders();
                throw ex;
              }
            }

          }
        }
        if (updatedOrders) {
          sortOrders(triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL), TransactionType.SELL, market,
              descendingStopPriceComparator);
          sortOrders(trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL), TransactionType.SELL, market,
              ascendingTrailingStopPriceComparator);

        }

      }

      if (triggerOrders.get(market) != null && !triggerOrders.get(market).isEmpty()) {
        if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null
            && triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null && !triggeredOrders.isEmpty()) {
          synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
            triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(triggeredOrders);
          }
          if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
              && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null)
            synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
              trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(triggeredOrders);
            }
        }
        if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null
            && triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null && !triggeredOrders.isEmpty()) {
          if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
              && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null)
            synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
              trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).removeAll(triggeredOrders);
            }
          synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
            triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).removeAll(triggeredOrders);
          }
        }
      }
      /*
       * if ((triggerOrders.get(market) != null && triggerOrders.get(market).get(TransactionType.SELL) != null && triggerOrders.get(market)
       * .get(TransactionType.SELL).get(parentKey) != null) && triggerOrders.get(market).get(TransactionType.SELL).get(parentKey).isEmpty())
       * triggeredParents.add(parentKey);
       */

      // triggerOrderLock.lock();

    }
    for (Event parent : triggeredParents) {
      if (triggerOrders.get(market) != null && triggerOrders.get(TransactionType.SELL) != null)
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).remove(parent);
        }
      if (triggerOrders.get(market) != null && triggerOrders.get(TransactionType.BUY) != null)
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).remove(parent);
        }

    }
    unlockTriggerOrders();

    //when running against mock order serice, we get an npe, so ether trigger orders is empty ot the parnet key is empty

    // triggerOrderLock.unlock();

    //triggerOrders.removeAll(triggeredOrders);
    //  }

  }

  private SpecificOrder convertGeneralOrderToSpecific(GeneralOrder generalOrder, Market market) {
    if (generalOrder.getVolume().isZero())

      return null;
    DiscreteAmount volume = generalOrder.getUnfilledVolume().toBasis(market.getVolumeBasis(), Remainder.DISCARD);

    DiscreteAmount discreteLimit;
    DiscreteAmount discreteMarket;
    DiscreteAmount discreteStop = null;
    RemainderHandler priceRemainderHandler = generalOrder.isBid() ? buyHandler : sellHandler;
    DecimalAmount limitPrice;
    if (generalOrder.getTargetPrice() != null)
      limitPrice = generalOrder.getTargetPrice();
    else
      limitPrice = generalOrder.getLimitPrice();

    final DecimalAmount marketPrice = generalOrder.getMarketPrice();

    final DecimalAmount stopPrice = generalOrder.getStopPrice();
    final DecimalAmount trailingStopPrice = generalOrder.getTrailingStopPrice();
    // DecimalAmount paretnFill = generalOrder.getTrailingStopPrice();

    // the volume will already be negative for a sell order

    SpecificOrder specificOrder = specificOrderFactory.create(context.getTime(), generalOrder.getPortfolio(), market, volume, generalOrder,
        generalOrder.getComment());

    // Market markettest = specificOrder.getMarket();
    specificOrder.withOrderGroup(generalOrder.getOrderGroup());
    specificOrder.withUsePosition(generalOrder.getUsePosition());
    specificOrder.withPositionEffect(generalOrder.getPositionEffect());
    specificOrder.withExecutionInstruction(generalOrder.getExecutionInstruction());
    switch (generalOrder.getFillType()) {
      case GOOD_TIL_CANCELLED:
        break;
      case GTC_OR_MARGIN_CAP:
        break;
      case CANCEL_REMAINDER:
        break;
      case LIMIT:
        discreteLimit = limitPrice.toBasis(market.getPriceBasis(), priceRemainderHandler);
        specificOrder.withLimitPrice(discreteLimit);
        generalOrder.setLastBestPriceDecimal(discreteLimit.asBigDecimal());
        break;
      case TRAILING_UNREALISED_STOP_LIMIT:
      case TRAILING_STOP_LIMIT:
      case REENTRANT_TRAILING_STOP_LIMIT:
      case STOP_LIMIT:
        //we will put the stop order in at best bid or best ask
        SpecificOrder stopOrder = specificOrder;
        //  stopOrder.persit();
        if (stopOrder.isBid())
          discreteStop = stopOrder.getExecutionInstruction() == (ExecutionInstruction.TAKER) ? quotes.getLastAskForMarket(stopOrder.getMarket())
              .getPrice() : quotes.getLastBidForMarket(stopOrder.getMarket()).getPrice();
        if (stopOrder.isAsk())
          discreteStop = stopOrder.getExecutionInstruction() == (ExecutionInstruction.TAKER) ? quotes.getLastAskForMarket(stopOrder.getMarket())
              .getPrice() : quotes.getLastBidForMarket(stopOrder.getMarket()).getPrice();

        if (discreteStop == null)
          break;
        // discreteStop = offer.getPrice();
        if (limitPrice != null) {
          discreteLimit = volume.isNegative() ? discreteStop.decrement(4) : discreteStop.increment(4);
          specificOrder.withLimitPrice(discreteLimit);
          generalOrder.setLastBestPriceDecimal(discreteLimit.asBigDecimal());
        } else {
          discreteMarket = marketPrice.toBasis(market.getPriceBasis(), priceRemainderHandler);
          specificOrder.withMarketPrice(discreteMarket);
          generalOrder.setLastBestPriceDecimal(discreteMarket.asBigDecimal());
        }

        break;
      case TRAILING_STOP_LOSS:
      case REENTRANT_TRAILING_STOP_LOSS:
      case TRAILING_UNREALISED_STOP_LOSS:
      case STOP_LOSS:
      case REENTRANT_STOP_LOSS:
        if (limitPrice != null) {
          discreteLimit = limitPrice.toBasis(market.getPriceBasis(), priceRemainderHandler);
          specificOrder.withLimitPrice(discreteLimit);
          generalOrder.setLastBestPriceDecimal(discreteLimit.asBigDecimal());

        } else {
          discreteMarket = marketPrice.toBasis(market.getPriceBasis(), priceRemainderHandler);
          specificOrder.withMarketPrice(discreteMarket);
          generalOrder.setLastBestPriceDecimal(discreteMarket.asBigDecimal());
        }
        break;
      case ONE_CANCELS_OTHER:
        //  builder.withFillType(FillType.ONE_CANCELS_OTHER);
        if (limitPrice != null) {
          discreteLimit = limitPrice.toBasis(market.getPriceBasis(), priceRemainderHandler);
          specificOrder.withLimitPrice(discreteLimit);
          generalOrder.setLastBestPriceDecimal(discreteLimit.asBigDecimal());
        } else {
          discreteMarket = marketPrice.toBasis(market.getPriceBasis(), priceRemainderHandler);
          specificOrder.withMarketPrice(discreteMarket);
          generalOrder.setLastBestPriceDecimal(discreteMarket.asBigDecimal());
        }

        break;
      case COMPLETED_CANCELS_OTHER:
        //  builder.withFillType(FillType.COMPLETED_CANCELS_OTHER);

        if (limitPrice != null) {
          discreteLimit = limitPrice.toBasis(market.getPriceBasis(), priceRemainderHandler);
          specificOrder.withLimitPrice(discreteLimit);
          generalOrder.setLastBestPriceDecimal(discreteLimit.asBigDecimal());
        } else {
          discreteMarket = marketPrice.toBasis(market.getPriceBasis(), priceRemainderHandler);
          specificOrder.withMarketPrice(discreteMarket);
          generalOrder.setLastBestPriceDecimal(discreteMarket.asBigDecimal());
        }

        break;
    }

    specificOrder.copyCommonOrderProperties(generalOrder);
    //specificOrder.persit();
    return specificOrder;
  }

  protected void reject(Order order, String message) {
    log.warn("Order " + order + " rejected: " + message);
    updateOrderState(order, OrderState.REJECTED, true);
  }

  protected void error(Order order, String message) {
    log.error("Order " + order + " errored: " + message);
    updateOrderState(order, OrderState.ERROR, true);
  }

  protected abstract void handleSpecificOrder(SpecificOrder specificOrder) throws Throwable;

  protected abstract OrderState getOrderStateFromOrderService(Order order) throws Throwable;

  protected abstract boolean cancelSpecificOrder(SpecificOrder specificOrder) throws Throwable;

  @Override
  public Collection<SpecificOrder> handleCancelAllSpecificOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> ordersToCancel = new ArrayList<>();
    if (market == null || portfolio == null)
      return ordersToCancel;
    // synchronized (lock) {
    // for (Order order : getPendingOrders()) {
    Iterator<Order> it = getPendingOrders().iterator();
    while (it.hasNext()) {
      Order order = it.next();

      //  for (Iterator<Order> it = getPendingOrders().iterator(); it.hasNext();) {
      //    Order order = it.next();
      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (specificOrder.getMarket().equals(market) && orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen()) {
          ordersToCancel.add(specificOrder);
          log.info("handleCancelAllSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2] + " cancelling order : "
              + specificOrder);
        }
      }
    }
    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(ordersToCancel);

    return cancelSpecificOrder(cancelledOrders);

  }

  //cancelledOrders.add(specificOrder);
  // updateOrderState(specificOrder, OrderState.CANCELLING);

  //          for (Iterator<SpecificOrder> it = cancelledOrders.iterator(); it.hasNext();) {
  //              SpecificOrder specificOrder = it.next();
  //
  //              
  //          }
  //  }

  @Override
  public Collection<SpecificOrder> getPendingOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;
        if (pendingOrder.getPortfolio().equals(portfolio) && pendingOrder.getMarket().equals(market)) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    return portfolioPendingOrders;

  }

  @Override
  public Collection<Fill> getFills(Market market, Portfolio portfolio) {
    List<Fill> portfolioFills = new ArrayList<Fill>();
    for (Order order : orderStateMap.keySet()) {
      if (order.getFills() != null && !order.getFills().isEmpty() && order.getPortfolio().equals(portfolio) && order instanceof SpecificOrder) {

        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.getMarket().equals(market))

          portfolioFills.addAll(pendingOrder.getFills());

      }
    }
    Collections.sort(portfolioFills, timeOrderIdComparator);
    return portfolioFills;
    // return portfolioFills;

    //  return null;

  }

  @Override
  public Collection<SpecificOrder> getPendingLongOpenOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;

    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.getPortfolio().equals(portfolio) && pendingOrder.getMarket().equals(market)
            && pendingOrder.getPositionEffect() == (PositionEffect.OPEN) && pendingOrder.isBid()) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    //     log.trace("getPendingLongOpenOrders pending long open orders : " + portfolioPendingOrders);

    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingLongOpenOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();

    if (market == null || portfolio == null)
      return portfolioPendingOrders;

    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.getPortfolio().equals(portfolio) && pendingOrder.getMarket().equals(market) && pendingOrder.getOrderGroup() == (orderGroup)
            && pendingOrder.getPositionEffect() == (PositionEffect.OPEN) && pendingOrder.isBid()) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    //     log.trace("getPendingLongOpenOrders pending long open orders : " + portfolioPendingOrders);

    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingLongOrders() {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();

    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.isBid()) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    //    log.trace("getPendingLongOrders pending long open orders : " + portfolioPendingOrders);

    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingLongCloseOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;

    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.getPortfolio().equals(portfolio) && pendingOrder.getMarket().equals(market)
            && pendingOrder.getPositionEffect() == (PositionEffect.CLOSE) && pendingOrder.isAsk()) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    //    log.trace("getPendingLongCloseOrders pending long close orders : " + portfolioPendingOrders);

    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingLongCloseOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;

    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.getPortfolio().equals(portfolio) && pendingOrder.getMarket().equals(market) && pendingOrder.getOrderGroup() == (orderGroup)
            && pendingOrder.getPositionEffect() == (PositionEffect.CLOSE) && pendingOrder.isAsk()) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    //    log.trace("getPendingLongCloseOrders pending long close orders : " + portfolioPendingOrders);

    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingShortOpenOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.getPortfolio().equals(portfolio) && pendingOrder.getMarket().equals(market)
            && pendingOrder.getPositionEffect() == (PositionEffect.OPEN) && pendingOrder.isAsk()) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    //      log.trace("getPendingShortOpenOrders pending short open orders : " + portfolioPendingOrders);

    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingShortOpenOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.getPortfolio().equals(portfolio) && pendingOrder.getMarket().equals(market) && pendingOrder.getOrderGroup() == orderGroup
            && pendingOrder.getPositionEffect() == (PositionEffect.OPEN) && pendingOrder.isAsk()) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    //      log.trace("getPendingShortOpenOrders pending short open orders : " + portfolioPendingOrders);

    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingShortOrders() {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.isAsk()) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }

    // log.debug("trace" + log.isTraceEnabled());
    // log.debug("trace" + log.isTraceEnabled());
    // log.trace("getPendingShortOrders pending short open orders : " + portfolioPendingOrders);

    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingShortCloseOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.getPortfolio().equals(portfolio) && pendingOrder.getMarket().equals(market)
            && pendingOrder.getPositionEffect() == (PositionEffect.CLOSE) && pendingOrder.isBid()) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    //    log.trace("getPendingShortCloseOrders pending short close orders : " + portfolioPendingOrders);

    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingShortCloseOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.getPortfolio().equals(portfolio) && pendingOrder.getMarket().equals(market) && pendingOrder.getOrderGroup() == orderGroup
            && pendingOrder.getPositionEffect() == (PositionEffect.CLOSE) && pendingOrder.isBid()) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    //    log.trace("getPendingShortCloseOrders pending short close orders : " + portfolioPendingOrders);

    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingCloseOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;

        if (pendingOrder.getPortfolio().equals(portfolio) && pendingOrder.getMarket().equals(market)
            && pendingOrder.getPositionEffect() == (PositionEffect.CLOSE)) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> handleCancelAllShortClosingSpecificOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    if (market == null || portfolio == null)
      return orderToCancel;
    //   synchronized (lock) {
    //  for (Order order:getPendingOrders()) {
    Iterator<Order> it = getPendingOrders().iterator();
    while (it.hasNext()) {
      Order order = it.next();

      // for (Iterator<Order> it = getPendingOrders().iterator(); it.hasNext();) {
      //   Order order = it.next();
      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (specificOrder.getMarket().equals(market) && (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen())
            && specificOrder.getPositionEffect() == (PositionEffect.CLOSE) && specificOrder.isBid()
            && (specificOrder.getExecutionInstruction() == null || (specificOrder.getExecutionInstruction() != null))) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllShortClosingSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2]
              + " cancelling order : " + specificOrder);

        }
      }
    }

    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);

    return cancelSpecificOrder(cancelledOrders);

  }

  @Override
  public Collection<SpecificOrder> handleCancelAllShortClosingSpecificOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    if (market == null || portfolio == null)
      return orderToCancel;
    //   synchronized (lock) {
    //  for (Order order:getPendingOrders()) {
    Iterator<Order> it = getPendingOrders().iterator();
    while (it.hasNext()) {
      Order order = it.next();

      // for (Iterator<Order> it = getPendingOrders().iterator(); it.hasNext();) {
      //   Order order = it.next();
      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (specificOrder.getMarket().equals(market) && specificOrder.getOrderGroup() == orderGroup
            && (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen())
            && specificOrder.getPositionEffect() == (PositionEffect.CLOSE) && specificOrder.isBid()
            && (specificOrder.getExecutionInstruction() == null || (specificOrder.getExecutionInstruction() != null))) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllShortClosingSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2]
              + " cancelling order : " + specificOrder);

        }
      }
    }

    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);

    return cancelSpecificOrder(cancelledOrders);

  }

  @Override
  public Collection<SpecificOrder> handleCancelAllShortClosingSpecificOrders(Portfolio portfolio, Market market, ExecutionInstruction execInst) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    if (market == null || portfolio == null)
      return orderToCancel;
    //   synchronized (lock) {
    //  for (Order order:getPendingOrders()) {
    Iterator<Order> it = getPendingOrders().iterator();
    while (it.hasNext()) {
      Order order = it.next();

      // for (Iterator<Order> it = getPendingOrders().iterator(); it.hasNext();) {
      //   Order order = it.next();
      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (specificOrder.getMarket().equals(market)
            && (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen())
            && specificOrder.getPositionEffect() == (PositionEffect.CLOSE)
            && specificOrder.isBid()
            && (specificOrder.getExecutionInstruction() == null || (specificOrder.getExecutionInstruction() != null && specificOrder
                .getExecutionInstruction().equals(execInst)))) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllShortClosingSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2]
              + " cancelling order : " + specificOrder);

        }
      }
    }

    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);

    return cancelSpecificOrder(cancelledOrders);

  }

  protected void handleUpdateSpecificOrderWorkingQuantity(SpecificOrder specificOrder, DiscreteAmount quantity) {
    for (Order pendingOrder : getPendingOrders()) {
      if (pendingOrder.equals(specificOrder)) {
        // so we need to ensure that the unfilled wuanity is waulty to the qauntity.

        // 200 lots order, 100 lots fill, want to update to 10 lots, so 110.
        long updatedQuantity = (quantity.isNegative()) ? -1
            * (Math.abs(specificOrder.getVolume().getCount()) - Math.abs(specificOrder.getUnfilledVolumeCount()) + Math.abs(quantity.getCount()))
            : Math.abs(specificOrder.getVolume().getCount()) - Math.abs(specificOrder.getUnfilledVolumeCount()) + Math.abs(quantity.getCount());

        //&& pendingOrder.getMarket().equals(market)) {
        specificOrder.setVolumeCount(updatedQuantity);

      }

    }

  }

  @Override
  public boolean handleCancelSpecificOrderByParentFill(Fill parentFill) {
    if (parentFill == null)
      return false;
    Set<Order> allChildOrders = new HashSet<Order>();
    parentFill.getAllSpecificOrdersByParentFill(parentFill, allChildOrders);
    Collection<SpecificOrder> ordersToCancel = new HashSet<SpecificOrder>();
    Collection<SpecificOrder> cancelledOrders;

    for (Order childOrder : allChildOrders) {
      if (childOrder instanceof SpecificOrder && orderStateMap.get(childOrder) != null && orderStateMap.get(childOrder).isOpen())
        ordersToCancel.add((SpecificOrder) childOrder);

    }
    boolean foundOrderToBeCancelled = false;
    if (ordersToCancel == null || ordersToCancel.isEmpty()) {
      return true;
    }

    cancelledOrders = cancelSpecificOrder(ordersToCancel);
    // if all the orders to cancelled have been canclled then return true;
    ORDERSTOCANCELLOOP: for (SpecificOrder orderToCancel : ordersToCancel) {
      // if all the orders to are in the cancelledOrders return ture
      foundOrderToBeCancelled = false;

      CANCELLEDORDERSLOOP: for (SpecificOrder cancelledOrder : cancelledOrders) {

        if (cancelledOrder.equals(orderToCancel)) {
          log.debug("handleCancelSpecificOrderByParentFill called from class " + Thread.currentThread().getStackTrace()[2] + " canclled order : "
              + orderToCancel);

          foundOrderToBeCancelled = true;
          break CANCELLEDORDERSLOOP;
        }
      }
      if (!foundOrderToBeCancelled) {
        log.debug("handleCancelSpecificOrderByParentFill called from class " + Thread.currentThread().getStackTrace()[2]
            + " unable to cancel order : " + orderToCancel);

        break ORDERSTOCANCELLOOP;
      }

    }
    return foundOrderToBeCancelled;

    //        while (itPending.hasNext()) {
    //            // closing position
    //            SpecificOrder cancelledOrder = itPending.next();
    //
    //            if (cancelledOrder != null && cancelledOrder.getParentFill() != null && cancelledOrder.getParentFill().equals(parentFill)) {
    //               
    //
    //                }
    //
    //            }
    //        }
  }

  @Override
  public boolean handleCancelOrders(Collection<Order> orders) {
    boolean ordersCancelled = true;
    if (orders == null)
      return ordersCancelled;
    for (Order order : orders) {
      if (order instanceof GeneralOrder) {
        if (!handleCancelGeneralOrder((GeneralOrder) order))
          ;
        ordersCancelled = false;
      } else if (order instanceof SpecificOrder) {
        if (!handleCancelSpecificOrder((SpecificOrder) order))
          ordersCancelled = false;
      }
    }
    return ordersCancelled;
  }

  @Override
  public boolean handleCancelOrder(Order order) {
    boolean ordersCancelled = true;
    if (order == null)
      return ordersCancelled;
    if (order instanceof GeneralOrder) {
      if (!handleCancelGeneralOrder((GeneralOrder) order))

        ordersCancelled = false;
    } else if (order instanceof SpecificOrder) {
      if (!handleCancelSpecificOrder((SpecificOrder) order))
        ordersCancelled = false;
    }

    return ordersCancelled;
  }

  @Override
  public Collection<SpecificOrder> handleCancelAllClosingSpecificOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    //   synchronized (lock) {
    //  for ( Order order: )

    Iterator<Order> it = getPendingOrders().iterator();
    while (it.hasNext()) {
      Order order = it.next();

      //   for (Iterator<Order> it = getPendingOrders().iterator(); it.hasNext();) {
      //     Order order = it.next();
      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (specificOrder.getMarket().equals(market) && specificOrder.getPositionEffect() == (PositionEffect.CLOSE)
            && (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen())) {
          //cancelledOrders.add(specificOrder);
          orderToCancel.add(specificOrder);
          log.debug("handleCancelAllClosingSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2] + " cancelling order : "
              + specificOrder);
        }
      }
    }
    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);

    return cancelSpecificOrder(cancelledOrders);

  }

  public static final Comparator<Order> ascendingStopPriceComparator = new Comparator<Order>() {
    // user when order is buy (so either a long trigger order or a short exit ord)
    // the trigger orders are highest in the list sorted from hifest to  lowest (
    //sort order from lowest stop price to highest, used when I want to trigger the lowest price frist.
    @Override
    public int compare(Order order, Order order2) {

      if (order2.getStopPrice() == null && order.getStopPrice() == null && order2.getTargetPrice() == null && order.getTargetPrice() == null) {
        return 0;
      }
      // this sort from lowest to highest 
      //Null values go to bottom of list.
      if (order.getTargetPrice() == null && order.getStopPrice() == null) {
        //|| order.getTargetPrice() == null) {
        //order > order2
        return 1;
      }

      //Null values go to bottom of list.
      if (order2.getTargetPrice() == null && order2.getStopPrice() == null) {
        //|| order2.getTargetPrice() == null) {
        //order < order2.
        return -1;
      }
      int pComp;
      // so order one has either a traget price or a stop price as so does order 2
      // let's compare the target pricess
      if (order2.getTargetPrice() != null && order.getTargetPrice() != null) {
        pComp = order.getTargetPrice().compareTo(order2.getTargetPrice());
        if (pComp != 0)
          return pComp;
        int tComp = order.getTime().compareTo(order2.getTime());
        if (tComp != 0)
          return tComp;
        return System.identityHashCode(order) - System.identityHashCode(order2);
      } else if (order2.getTargetPrice() != null && order.getStopPrice() != null) {
        pComp = order.getStopPrice().compareTo(order2.getTargetPrice());
        if (pComp != 0)
          return pComp;
        int tComp = order.getTime().compareTo(order2.getTime());
        if (tComp != 0)
          return tComp;
        return System.identityHashCode(order) - System.identityHashCode(order2);
      } else if (order2.getStopPrice() != null && order.getTargetPrice() != null) {
        pComp = order.getTargetPrice().compareTo(order2.getStopPrice());
        if (pComp != 0)
          return pComp;
        int tComp = order.getTime().compareTo(order2.getTime());
        if (tComp != 0)
          return tComp;
        return System.identityHashCode(order) - System.identityHashCode(order2);
      } else
      /*
       * if (order2.getTargetPrice() != null) { return 1; } if (order.getTargetPrice() != null) { return -1; }
       */
      if (order2.getStopPrice() != null && order.getStopPrice() != null) {
        pComp = order.getStopPrice().compareTo(order2.getStopPrice());
        if (pComp != 0)
          return pComp;
        int tComp = order.getTime().compareTo(order2.getTime());
        if (tComp != 0)
          return tComp;
        return System.identityHashCode(order) - System.identityHashCode(order2);
      }

      // so we could have order 1 with stop and order 2 as target

      return 0;

      // we could have order 1 as traget and order 2 as stop

    }

  };
  // sorts the highest to lowest price, so if we are buying, our higest price will get filled first
  public static final Comparator<Order> descendingStopPriceComparator = new Comparator<Order>() {
    //sort order from highest stop price to lowest,used when I want to trigger the highest price frist.
    @Override
    public int compare(Order order, Order order2) {
      if (order2.getStopPrice() == null && order.getStopPrice() == null && order2.getTargetPrice() == null && order.getTargetPrice() == null) {
        return 0;
      }
      // this sort from lowest to highest 
      //Null values go to bottom of list.
      if (order.getTargetPrice() == null && order.getStopPrice() == null) {
        //|| order.getTargetPrice() == null) {
        //order > order2
        return 1;
      }

      //Null values go to bottom of list.
      if (order2.getTargetPrice() == null && order2.getStopPrice() == null) {
        //|| order2.getTargetPrice() == null) {
        //order < order2.
        return -1;
      }
      int pComp;
      // so order one has either a traget price or a stop price as so does order 2
      // let's compare the target pricess
      if (order2.getTargetPrice() != null && order.getTargetPrice() != null) {
        pComp = order2.getTargetPrice().compareTo(order.getTargetPrice());
        if (pComp != 0)
          return pComp;
        int tComp = order.getTime().compareTo(order2.getTime());
        if (tComp != 0)
          return tComp;
        return System.identityHashCode(order) - System.identityHashCode(order2);
      } else if (order2.getStopPrice() != null && order.getTargetPrice() != null) {
        pComp = order2.getStopPrice().compareTo(order.getTargetPrice());
        if (pComp != 0)
          return pComp;
        int tComp = order.getTime().compareTo(order2.getTime());
        if (tComp != 0)
          return tComp;
        return System.identityHashCode(order) - System.identityHashCode(order2);
      } else if (order2.getTargetPrice() != null && order.getStopPrice() != null) {
        pComp = order2.getTargetPrice().compareTo(order.getStopPrice());
        if (pComp != 0)
          return pComp;
        int tComp = order.getTime().compareTo(order2.getTime());
        if (tComp != 0)
          return tComp;
        return System.identityHashCode(order) - System.identityHashCode(order2);
      } else if (order2.getStopPrice() != null && order.getStopPrice() != null) {
        pComp = order2.getStopPrice().compareTo(order.getStopPrice());
        if (pComp != 0)
          return pComp;
        int tComp = order.getTime().compareTo(order2.getTime());
        if (tComp != 0)
          return tComp;
        return System.identityHashCode(order) - System.identityHashCode(order2);
      }

      // so we could have order 1 with stop and order 2 as target

      return 0;

      // we could have order 1 as traget and order 2 as stop

    }

  };

  public static final Comparator<Order> ascendingTrailingStopPriceComparator = new Comparator<Order>() {
    //sort order from lowest stop price to highest, used when I want to trigger the lowest price frist.
    @Override
    public int compare(Order order, Order order2) {
      if (order2.getStopPrice() == null && order.getStopPrice() == null) {
        return 0;
      }
      // if the stop price of order is null put to bottom of list.
      if (order.getStopPrice() == null) {
        return -1;
      }
      if (order2.getStopPrice() == null) {
        return 1;
      }
      int pComp = 0;
      if (order.getStopAmount() == null && order2.getStopAmount() == null) {
        // sort as ascending stop prices
        pComp = (order.isBid() && order2.isBid()) ? (order.getStopPrice()).compareTo(order2.getStopPrice()) : (order.getStopPrice()).compareTo(order2
            .getStopPrice());

      } else if (order.getStopAmount() == null) {
        //sort as one asecnding sided stop
        pComp = (order.isBid() && order2.isBid()) ? (order.getStopPrice()).compareTo(order2.getStopPrice().minus(order2.getStopAmount())) : (order
            .getStopPrice()).compareTo(order2.getStopPrice().plus(order2.getStopAmount()));

      } else if (order2.getStopAmount() == null) {
        //sort as acseinding one sided stop
        pComp = (order.isBid() && order2.isBid()) ? (order.getStopPrice().minus(order.getStopAmount())).compareTo(order2.getStopPrice()) : (order
            .getStopPrice().plus(order.getStopAmount())).compareTo(order2.getStopPrice());

      } else

        pComp = (order.isBid() && order2.isBid()) ? (order.getStopPrice().minus(order.getStopAmount())).compareTo(order2.getStopPrice().minus(
            order2.getStopAmount())) : (order.getStopPrice().plus(order.getStopAmount())).compareTo(order2.getStopPrice()
            .plus(order2.getStopAmount()));
      if (pComp != 0)
        return pComp;
      int tComp = order.getTime().compareTo(order2.getTime());
      if (tComp != 0)
        return tComp;
      return System.identityHashCode(order) - System.identityHashCode(order2);

    }

  };
  // sorts the highest to lowest price, so if we are buying, our higest price will get filled first
  public static final Comparator<Order> descendingTrailingStopPriceComparator = new Comparator<Order>() {
    //sort order from highest stop price to lowest,used when I want to trigger the highest price frist.
    @Override
    public int compare(Order order, Order order2) {

      if (order2.getStopPrice() == null && order.getStopPrice() == null) {
        return 0;
      }
      // if the stop price of order is null put to bottom of list.
      if (order.getStopPrice() == null) {
        return -1;
      }
      if (order2.getStopPrice() == null) {
        return 1;
      }
      int pComp = 0;
      if (order.getStopAmount() == null && order2.getStopAmount() == null) {
        // sort as ascending stop prices
        pComp = (order.isBid() && order2.isBid()) ? (order2.getStopPrice()).compareTo(order.getStopPrice()) : (order2.getStopPrice()).compareTo(order
            .getStopPrice());

      } else if (order.getStopAmount() == null) {
        //sort as one asecnding sided stop
        pComp = (order.isBid() && order2.isBid()) ? (order2.getStopPrice().minus(order2.getStopAmount())).compareTo(order.getStopPrice()) : (order2
            .getStopPrice().plus(order2.getStopAmount())).compareTo(order.getStopPrice());

      } else if (order2.getStopAmount() == null) {
        //sort as acseinding one sided stop
        pComp = (order.isBid() && order2.isBid()) ? (order2.getStopPrice()).compareTo(order.getStopPrice().minus(order.getStopAmount())) : (order2
            .getStopPrice()).compareTo(order.getStopPrice().plus(order.getStopAmount()));

      } else

        pComp = (order.isBid() && order2.isBid()) ? (order2.getStopPrice().minus(order2.getStopAmount())).compareTo(order.getStopPrice().minus(
            order.getStopAmount())) : (order2.getStopPrice().plus(order2.getStopAmount()))
            .compareTo(order.getStopPrice().plus(order.getStopAmount()));
      if (pComp != 0)
        return pComp;
      int tComp = order2.getTime().compareTo(order.getTime());
      if (tComp != 0)
        return tComp;
      return System.identityHashCode(order2) - System.identityHashCode(order);

    }

  };

  public static final Comparator<Amount> ascendingAmountComparator = new Comparator<Amount>() {
    //sort order from lowest stop price to highest, used when I want to trigger the lowest price frist.
    @Override
    public int compare(Amount amount, Amount amount2) {
      return (amount.compareTo(amount2));
    }

  };
  // sorts the highest to lowest price, so if we are buying, our higest price will get filled first
  public static final Comparator<Amount> descendingAmountComparator = new Comparator<Amount>() {
    //sort order from highest stop price to lowest,used when I want to trigger the highest price frist.
    @Override
    public int compare(Amount amount, Amount amount2) {

      return (amount2.compareTo(amount));

    }

  };
  // sorts the lowest to highest price so if we are selling, the lowest price will get filled first.
  public static final Comparator<Order> ascendingPriceComparator = new Comparator<Order>() {
    // Order fills oldest first (lower time), then have the biggest quanity first to close out.
    @Override
    public int compare(Order order, Order order2) {
      if (order2.getLimitPrice() == null && order.getLimitPrice() == null) {
        return 0;
      }
      // this sort from lowest to highest 
      //Null values go to bottom of list.
      if (order.getLimitPrice() == null) {
        //|| order.getTargetPrice() == null) {
        //order > order2
        return -1;
      }

      //Null values go to bottom of list.
      if (order2.getLimitPrice() == null) {
        //|| order2.getTargetPrice() == null) {
        //order < order2.
        return 1;
      }
      int pComp = order.getLimitPrice().compareTo(order2.getLimitPrice());
      if (pComp != 0)
        return pComp;

      int tComp = order.getTime().compareTo(order2.getTime());
      if (tComp != 0)
        return tComp;

      return System.identityHashCode(order) - System.identityHashCode(order2);
    }
  }

  ;
  // sorts the highest to lowest price, so if we are buying, our higest price will get filled first
  public static final Comparator<Order> descendingPriceComparator = new Comparator<Order>() {
    // Order fills oldest first (lower time), then have the biggest quanity first to close out.
    @Override
    public int compare(Order order, Order order2) {
      if (order2.getLimitPrice() == null && order.getLimitPrice() == null) {
        return 0;
      }
      // this sort from lowest to highest 
      //Null values go to bottom of list.
      if (order.getLimitPrice() == null) {
        //|| order.getTargetPrice() == null) {
        //order > order2
        return -1;
      }

      //Null values go to bottom of list.
      if (order2.getLimitPrice() == null) {
        //|| order2.getTargetPrice() == null) {
        //order < order2.
        return 1;
      }

      int pComp = order2.getLimitPrice().compareTo(order.getLimitPrice());
      if (pComp != 0)
        return pComp;
      int tComp = order.getTime().compareTo(order2.getTime());
      if (tComp != 0)
        return tComp;

      return System.identityHashCode(order) - System.identityHashCode(order2);
      //   order.hashCode() - order2.hashCode();

    }

  };

  @Override
  public Collection<SpecificOrder> handleCancelAllLongOpeningSpecificOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    //  synchronized (lock) {
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (specificOrder.getMarket().equals(market) && specificOrder.getPositionEffect() == (PositionEffect.OPEN) && specificOrder.isBid()
            && orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen()) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllLongOpeningSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2] + " cancelling order : "
              + specificOrder);
        }

      }
    }
    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);
    return cancelSpecificOrder(cancelledOrders);

  }

  @Override
  public Collection<SpecificOrder> handleCancelAllLongOpeningSpecificOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    //  synchronized (lock) {
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (specificOrder.getMarket().equals(market) && specificOrder.getOrderGroup() == orderGroup
            && specificOrder.getPositionEffect() == (PositionEffect.OPEN) && specificOrder.isBid() && orderStateMap.get(specificOrder) != null
            && orderStateMap.get(specificOrder).isOpen()) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllLongOpeningSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2] + " cancelling order : "
              + specificOrder);
        }

      }
    }
    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);
    return cancelSpecificOrder(cancelledOrders);

  }

  @Override
  public Collection<SpecificOrder> getPendingLongCloseOrders(Portfolio portfolio, ExecutionInstruction execInst, Market market) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;

    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;
        if (pendingOrder.getPortfolio().equals(portfolio)
            && pendingOrder.getMarket().equals(market)
            && pendingOrder.getPositionEffect() == (PositionEffect.CLOSE)
            && pendingOrder.isAsk()
            && (pendingOrder.getExecutionInstruction() == null || (pendingOrder.getExecutionInstruction() != null && pendingOrder
                .getExecutionInstruction().equals(execInst)))) {
          portfolioPendingOrders.add(pendingOrder);

        }

      }
    }
    // orderStateMap.remove(pendingOrder);
    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingLongCloseOrders(Portfolio portfolio, ExecutionInstruction execInst, Market market, double orderGroup) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;

    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;
        if (pendingOrder.getPortfolio().equals(portfolio)
            && pendingOrder.getMarket().equals(market)
            && pendingOrder.getOrderGroup() == orderGroup
            && pendingOrder.getPositionEffect() == (PositionEffect.CLOSE)
            && pendingOrder.isAsk()
            && (pendingOrder.getExecutionInstruction() == null || (pendingOrder.getExecutionInstruction() != null && pendingOrder
                .getExecutionInstruction().equals(execInst)))) {
          portfolioPendingOrders.add(pendingOrder);

        }

      }
    }
    // orderStateMap.remove(pendingOrder);
    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingShortCloseOrders(Portfolio portfolio, ExecutionInstruction execInst, Market market) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;
        if (pendingOrder.getPortfolio().equals(portfolio)
            && pendingOrder.getMarket().equals(market)
            && pendingOrder.getPositionEffect() == (PositionEffect.CLOSE)
            && pendingOrder.isBid()
            && (pendingOrder.getExecutionInstruction() == null || (pendingOrder.getExecutionInstruction() != null && pendingOrder
                .getExecutionInstruction().equals(execInst)))) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> getPendingShortCloseOrders(Portfolio portfolio, ExecutionInstruction execInst, Market market, double orderGroup) {
    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<SpecificOrder>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;
        if (pendingOrder.getPortfolio().equals(portfolio)
            && pendingOrder.getMarket().equals(market)
            && pendingOrder.getOrderGroup() == orderGroup
            && pendingOrder.getPositionEffect() == (PositionEffect.CLOSE)
            && pendingOrder.isBid()
            && (pendingOrder.getExecutionInstruction() == null || (pendingOrder.getExecutionInstruction() != null && pendingOrder
                .getExecutionInstruction().equals(execInst)))) {

          portfolioPendingOrders.add(pendingOrder);
        }

      }
    }
    return portfolioPendingOrders;
  }

  @Override
  public Collection<SpecificOrder> handleCancelAllLongClosingSpecificOrders(Portfolio portfolio, Market market, ExecutionInstruction execInst) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    Iterator<Order> it = getPendingOrders().iterator();
    while (it.hasNext()) {
      Order order = it.next();

      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (specificOrder.getMarket().equals(market)
            && (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen())
            && specificOrder.getPositionEffect() == (PositionEffect.CLOSE)
            && specificOrder.isAsk()
            && (specificOrder.getExecutionInstruction() == null || (specificOrder.getExecutionInstruction() != null && specificOrder
                .getExecutionInstruction().equals(execInst)))) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllLongClosingSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2] + " cancelling order : "
              + specificOrder);
        }
      }
    }
    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);

    return cancelSpecificOrder(cancelledOrders);

  }

  @Override
  public Collection<SpecificOrder> handleCancelAllLongClosingSpecificOrders(Portfolio portfolio, Market market, ExecutionInstruction execInst,
      double orderGroup) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    Iterator<Order> it = getPendingOrders().iterator();
    while (it.hasNext()) {
      Order order = it.next();

      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (specificOrder.getOrderGroup() == orderGroup
            && specificOrder.getMarket().equals(market)
            && (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen())
            && specificOrder.getPositionEffect() == (PositionEffect.CLOSE)
            && specificOrder.isAsk()
            && (specificOrder.getExecutionInstruction() == null || (specificOrder.getExecutionInstruction() != null && specificOrder
                .getExecutionInstruction().equals(execInst)))) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllLongClosingSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2] + " cancelling order : "
              + specificOrder);
        }
      }
    }
    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);

    return cancelSpecificOrder(cancelledOrders);

  }

  @Override
  public Collection<SpecificOrder> handleCancelAllLongClosingSpecificOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    //   synchronized (lock) {
    // for (Iterator<Order> it = getPendingOrders().iterator(); it.hasNext();) {
    //   Order order = it.next();
    Iterator<Order> it = getPendingOrders().iterator();
    while (it.hasNext()) {
      Order order = it.next();

      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (specificOrder.getMarket().equals(market) && (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen())
            && specificOrder.getPositionEffect() == (PositionEffect.CLOSE) && specificOrder.isAsk()
            && (specificOrder.getExecutionInstruction() == null || (specificOrder.getExecutionInstruction() != null))) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllLongClosingSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2] + " cancelling order : "
              + specificOrder);
        }
      }
    }
    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);

    return cancelSpecificOrder(cancelledOrders);

  }

  @Override
  public Collection<SpecificOrder> handleCancelAllLongClosingSpecificOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    //   synchronized (lock) {
    // for (Iterator<Order> it = getPendingOrders().iterator(); it.hasNext();) {
    //   Order order = it.next();
    Iterator<Order> it = getPendingOrders().iterator();
    while (it.hasNext()) {
      Order order = it.next();

      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (specificOrder.getMarket().equals(market) && specificOrder.getOrderGroup() == orderGroup
            && (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen())
            && specificOrder.getPositionEffect() == (PositionEffect.CLOSE) && specificOrder.isAsk()
            && (specificOrder.getExecutionInstruction() == null || (specificOrder.getExecutionInstruction() != null))) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllLongClosingSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2] + " cancelling order : "
              + specificOrder);
        }
      }
    }
    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);

    return cancelSpecificOrder(cancelledOrders);

  }

  @Override
  public boolean handleCancelSpecificOrder(SpecificOrder specificOrder) {
    try {
      if (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen() && cancelSpecificOrder(specificOrder)) {
        if (specificOrder.getParentFill() != null)
          specificOrder.getParentFill().setPositionType(
              (specificOrder.getParentFill().getOpenVolumeCount() == 0 ? PositionType.FLAT
                  : (specificOrder.getParentFill().getOpenVolumeCount() > 0 ? PositionType.LONG : PositionType.SHORT)));
        // need to remove it from any parent
        //            if (specificOrder.getParentOrder() != null && specificOrder.getParentOrder().getOrderChildren() != null
        //                    && !specificOrder.getParentOrder().getOrderChildren().isEmpty())
        //
        //                specificOrder.getParentOrder().removeChildOrder(specificOrder);

        log.info("handleCancelSpecificOrder cancelled called from class " + Thread.currentThread().getStackTrace()[2] + " Specific Order:"
            + specificOrder);
        return true;
      }

      //   } else if (getOrderState(specificOrder).isNew()) {
      //     log.info("handleCancelSpecificOrder unable to cancelled " + specificOrder + " with order state " + getOrderState(specificOrder).toString()
      //             + " so force cancelling");
      //     return true;
      // }

      else {
        log.info("handleCancelSpecificOrder called from class " + Thread.currentThread().getStackTrace()[2] + " unable to cancelled Specific Order:"
            + specificOrder);
        return false;
      }
    } catch (IllegalArgumentException ex) {
      try {
        // if (getOrderState(specificOrder) != null && getOrderState(specificOrder).isNew()) {
        log.info("handleCancelSpecificOrder unable to cancelled " + specificOrder + " with order state " + getOrderState(specificOrder).toString()
            + " so force rejecting. Exception: " + ex);
        if (specificOrder.getParentFill() != null)
          specificOrder.getParentFill().setPositionType(
              (specificOrder.getParentFill().getOpenVolumeCount() == 0 ? PositionType.FLAT
                  : (specificOrder.getParentFill().getOpenVolumeCount() > 0 ? PositionType.LONG : PositionType.SHORT)));

        updateOrderState(specificOrder, OrderState.REJECTED, true);
        return true;

        /*
         * else log.info("handleCancelSpecificOrder called from class " + Thread.currentThread().getStackTrace()[2] + " unable to cancel state " +
         * getOrderState(specificOrder).toString() + " for " + specificOrder.getClass().getSimpleName() + ": " + specificOrder); return false; } catch
         * (IllegalArgumentException | IllegalStateException ise) { log.info("handleCancelSpecificOrder called from class " +
         * Thread.currentThread().getStackTrace()[2] + " unable to cancel " + specificOrder + " as unknown order so force rejecting. Exception: " +
         * ex); if (specificOrder.getParentFill() != null) specificOrder.getParentFill().setPositionType(
         * (specificOrder.getParentFill().getOpenVolumeCount() == 0 ? PositionType.FLAT : (specificOrder.getParentFill().getOpenVolumeCount() > 0 ?
         * PositionType.LONG : PositionType.SHORT))); updateOrderState(specificOrder, OrderState.REJECTED, true); return true;
         */
      } catch (Throwable t) {
        log.info("handleCancelSpecificOrder called from class " + Thread.currentThread().getStackTrace()[2] + " unable to cancel state "
            + getOrderState(specificOrder).toString() + " for " + specificOrder.getClass().getSimpleName() + ": " + specificOrder);
        return false;
      }
    } catch (Throwable t) {
      log.info("handleCancelSpecificOrder called from class " + Thread.currentThread().getStackTrace()[2] + " unable to cancel state "
          + getOrderState(specificOrder).toString() + " for " + specificOrder.getClass().getSimpleName() + ": " + specificOrder);
      return false;
    }
    // throw new OrderNotFoundException("Unable to cancelled order");
    //cancelledOrders.add(cancelledOrder);
    // pendingOrders.removeAll(cancelledOrders);

  }

  // @When("@Priority(7) select * from OrderUpdate where state.open=false and NOT (OrderUpdate.state = OrderState.CANCELLED)")
  private synchronized void completeOrder(OrderUpdate update) {
    OrderState orderState = update.getState();
    Order order = update.getOrder();
    SpecificOrder specificOrder;
    if (order instanceof SpecificOrder) {
      specificOrder = (SpecificOrder) order;
      try {
        switch (orderState) {
          case CANCELLING:
            if (handleCancelSpecificOrder(specificOrder))
              updateOrderState(order, OrderState.CANCELLED, true);
            break;
          default:
            handleCancelSpecificOrder(specificOrder);
            break;
        }
      } catch (Throwable ex) {
        log.info("completeOrder called from class " + Thread.currentThread().getStackTrace()[2] + " unable to cancelled Specific Order:"
            + specificOrder);
      }
    }
  }

  @Override
  public Collection<SpecificOrder> getPendingOrders(Market market, Portfolio portfolio) {

    Collection<SpecificOrder> portfolioPendingOrders = new ArrayList<>();
    if (market == null || portfolio == null)
      return portfolioPendingOrders;

    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder pendingOrder = (SpecificOrder) order;
        if (pendingOrder.getPortfolio().equals(portfolio) && pendingOrder.getMarket().equals(market))
          portfolioPendingOrders.add(pendingOrder);

      }
    }

    return portfolioPendingOrders;

  }

  @Override
  public Collection<Order> getPendingStopOrders(Portfolio portfolio, Market market) {
    Collection<Order> portfolioPendingStopOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return portfolioPendingStopOrders;
    Fill parentFill;
    Order parentOrder;
    //synchronized (lock) {
    log.debug(this.getClass().getSimpleName() + " : getPendingStopOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return portfolioPendingStopOrders;
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();

      for (Iterator<TransactionType> ittt = triggerOrders.get(market).get(triggerInterval).keySet().iterator(); ittt.hasNext();) {
        TransactionType transactionType = ittt.next();
        synchronized (triggerOrders.get(market).get(triggerInterval).get(transactionType)) {
          for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(transactionType).iterator(); it.hasNext();) {

            Order triggerOrder = it.next();

            if (triggerOrder.getPortfolio().equals(portfolio) && triggerOrder.getMarket().equals(market))

              portfolioPendingStopOrders.add(triggerOrder);
            //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill

          }
        }
      }
    }
    return portfolioPendingStopOrders;
  }

  @Override
  public Collection<Order> getPendingLongStopOrders(Portfolio portfolio, Market market) {
    Collection<Order> portfolioPendingStopOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return portfolioPendingStopOrders;

    Fill parentFill;
    Order parentOrder;
    //synchronized (lock) {
    log.debug(this.getClass().getSimpleName() + " : getPendingLongStopOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return portfolioPendingStopOrders;
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        return portfolioPendingStopOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator(); it.hasNext();) {
          Order triggerOrder = it.next();
          if (triggerOrder.getPortfolio().equals(portfolio) && triggerOrder.getMarket().equals(market) && triggerOrder.isAsk()
              && triggerOrder.getStopPrice() != null)

            portfolioPendingStopOrders.add(triggerOrder);

        }
      }
    }

    return portfolioPendingStopOrders;
  }

  @Override
  public Collection<Order> getPendingLongStopOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<Order> portfolioPendingStopOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return portfolioPendingStopOrders;

    Fill parentFill;
    Order parentOrder;
    //synchronized (lock) {
    log.debug(this.getClass().getSimpleName() + " : getPendingLongStopOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return portfolioPendingStopOrders;
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        return portfolioPendingStopOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator(); it.hasNext();) {
          Order triggerOrder = it.next();
          if (triggerOrder.getPortfolio().equals(portfolio) && triggerOrder.getMarket().equals(market) && triggerOrder.getOrderGroup() == orderGroup
              && triggerOrder.isAsk() && triggerOrder.getStopPrice() != null)

            portfolioPendingStopOrders.add(triggerOrder);

        }
      }
    }

    return portfolioPendingStopOrders;
  }

  @Override
  public Collection<Order> getPendingLongTriggerOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<Order> portfolioPendingTriggerOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return portfolioPendingTriggerOrders;
    Fill parentFill;
    Order parentOrder;
    //synchronized (lock) {
    log.debug(this.getClass().getSimpleName() + " : getPendingLongTriggerOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return portfolioPendingTriggerOrders;
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
        return portfolioPendingTriggerOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator(); it.hasNext();) {
          Order triggerOrder = it.next();
          if (triggerOrder.getPortfolio().equals(portfolio) && triggerOrder.getMarket().equals(market) && triggerOrder.getOrderGroup() == orderGroup
              && triggerOrder.isAsk() && triggerOrder.getTargetPrice() != null)

            portfolioPendingTriggerOrders.add(triggerOrder);

        }
      }
    }

    return portfolioPendingTriggerOrders;
  }

  @Override
  public Collection<Order> getRoutedLongStopOrders(Portfolio portfolio, Market market) {
    Collection<Order> portfolioPendingStopOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return portfolioPendingStopOrders;
    Fill parentFill;
    Order parentOrder;

    //stateOrderMap.get(OrderState.)
    //synchronized (lock) {
    log.debug(this.getClass().getSimpleName() + " : getRoutedShortStopOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return portfolioPendingStopOrders;
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        return portfolioPendingStopOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator(); it.hasNext();) {

          Order triggerOrder = it.next();
          if (triggerOrder.getPortfolio().equals(portfolio) && triggerOrder.getMarket().equals(market) && triggerOrder.isAsk()
              && triggerOrder.getStopPrice() != null && orderStateMap.get(triggerOrder).equals(OrderState.ROUTED))

            portfolioPendingStopOrders.add(triggerOrder);

        }
      }
    }

    return portfolioPendingStopOrders;
  }

  @Override
  public Collection<Order> getRoutedShortStopOrders(Portfolio portfolio, Market market) {
    Collection<Order> portfolioPendingStopOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return portfolioPendingStopOrders;
    Fill parentFill;
    Order parentOrder;

    //stateOrderMap.get(OrderState.)
    //synchronized (lock) {
    log.debug(this.getClass().getSimpleName() + " : getRoutedShortStopOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return portfolioPendingStopOrders;
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
        return portfolioPendingStopOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator(); it.hasNext();) {

          Order triggerOrder = it.next();
          if (triggerOrder.getPortfolio().equals(portfolio) && triggerOrder.getMarket().equals(market) && triggerOrder.isBid()
              && triggerOrder.getStopPrice() != null && orderStateMap.get(triggerOrder).equals(OrderState.ROUTED))

            portfolioPendingStopOrders.add(triggerOrder);

        }
      }
    }

    return portfolioPendingStopOrders;
  }

  @Override
  public Collection<Order> getPendingShortStopOrders(Portfolio portfolio, Market market) {
    Collection<Order> portfolioPendingStopOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return portfolioPendingStopOrders;

    Fill parentFill;
    Order parentOrder;
    //synchronized (lock) {
    log.debug(this.getClass().getSimpleName() + " : getPendingShortStopOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return portfolioPendingStopOrders;
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
        return portfolioPendingStopOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator(); it.hasNext();) {

          Order triggerOrder = it.next();
          if (triggerOrder.getPortfolio().equals(portfolio) && triggerOrder.getMarket().equals(market) && triggerOrder.isBid()
              && triggerOrder.getStopPrice() != null)

            portfolioPendingStopOrders.add(triggerOrder);

        }
      }
    }

    return portfolioPendingStopOrders;
  }

  @Override
  public Collection<Order> getPendingShortStopOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<Order> portfolioPendingStopOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return portfolioPendingStopOrders;
    Fill parentFill;
    Order parentOrder;
    //synchronized (lock) {
    log.debug(this.getClass().getSimpleName() + " : getPendingShortStopOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return portfolioPendingStopOrders;
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
        return portfolioPendingStopOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator(); it.hasNext();) {

          Order triggerOrder = it.next();
          if (triggerOrder.getPortfolio().equals(portfolio) && triggerOrder.getMarket().equals(market) && triggerOrder.getOrderGroup() == orderGroup
              && triggerOrder.isBid() && triggerOrder.getStopPrice() != null)

            portfolioPendingStopOrders.add(triggerOrder);

        }
      }
    }

    return portfolioPendingStopOrders;
  }

  @Override
  public Collection<Order> getPendingShortTriggerOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<Order> portfolioPendingTriggerOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return portfolioPendingTriggerOrders;
    Fill parentFill;
    Order parentOrder;
    //synchronized (lock) {
    log.debug(this.getClass().getSimpleName() + " : getPendingShortTriggerOrders to called from stack " + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return portfolioPendingTriggerOrders;
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        return portfolioPendingTriggerOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator(); it.hasNext();) {

          Order triggerOrder = it.next();
          if (triggerOrder.getPortfolio().equals(portfolio) && triggerOrder.getMarket().equals(market) && triggerOrder.getOrderGroup() == orderGroup
              && triggerOrder.isBid() && triggerOrder.getTargetPrice() != null)

            portfolioPendingTriggerOrders.add(triggerOrder);

        }
      }
    }

    return portfolioPendingTriggerOrders;
  }

  @Override
  public Collection<Order> handleCancelAllLongOpeningGeneralOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<Order> cancelledOrders = new ConcurrentLinkedQueue<>();
    //synchronized (lock) {
    //triggerOrderLock.lock();
    log.debug(this.getClass().getSimpleName() + " : handleCancelAllLongOpeningGeneralOrders to called from stack "
        + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return cancelledOrders;

    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
        return cancelledOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator(); it.hasNext();) {

          Order triggerOrder = it.next();
          //npe jerere
          if (triggerOrder != null && triggerOrder.getOrderGroup() == orderGroup && triggerOrder.getMarket().equals(market) && triggerOrder.isBid()
              && triggerOrder.getPositionEffect() == (PositionEffect.OPEN))

            cancelledOrders.add(triggerOrder);

        }
      }

      // (triggerOrders.get(parentKey)) {
      if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
          && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null)
        synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).removeAll(cancelledOrders);
        }
      if (triggerOrders != null && triggerOrders.get(market) != null
          && triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null
          && !triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).removeAll(cancelledOrders);
        }
    }
    //    }
    for (Order cancelledOrder : cancelledOrders) {
      updateOrderState(cancelledOrder, OrderState.CANCELLED, true);
      log.info("handleCancelAllLongOpeningGeneralOrders called from class " + Thread.currentThread().getStackTrace()[2]
          + "Cancelled Long Opening Trigger Order: " + cancelledOrder);
      if (cancelledOrder.getParentFill() != null)
        handleCancelAllTriggerOrdersByParentFill(cancelledOrder.getParentFill());

    }

    //                if(parentKey instanceof Fill) 
    //                      parentFill = (Fill) parentKey;
    //                if(parentKey instanceof Order) 
    //                     parentOrder = (Order) parentKey;
    //                if ((parentFill!=null && parentFill.getMarket().equals(market)) || (parentOrder!=null && parentOrder.getMarket().equals(market)))
    //                    triggerOrders.remove(parentKey);

    //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill

    return cancelledOrders;
    // triggerOrderLock.unlock();
    // }

  }

  @Override
  public Collection<Order> handleCancelAllLongOpeningGeneralOrders(Portfolio portfolio, Market market) {
    Collection<Order> cancelledOrders = new ConcurrentLinkedQueue<>();
    //synchronized (lock) {
    //triggerOrderLock.lock();
    log.debug(this.getClass().getSimpleName() + " : handleCancelAllLongOpeningGeneralOrders to called from stack "
        + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return cancelledOrders;
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
        return cancelledOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).iterator(); it.hasNext();) {

          Order triggerOrder = it.next();
          //npe jerere
          if (triggerOrder != null && triggerOrder.getMarket().equals(market) && triggerOrder.isBid()
              && triggerOrder.getPositionEffect() == (PositionEffect.OPEN))

            cancelledOrders.add(triggerOrder);

        }
      }
      // (triggerOrders.get(parentKey)) {
      if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
          && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null)
        synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).removeAll(cancelledOrders);
        }
      if (triggerOrders != null && triggerOrders.get(market) != null
          && triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null
          && !triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).isEmpty())
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY)) {
          triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY).removeAll(cancelledOrders);
        }
    }
    //    }
    for (Order cancelledOrder : cancelledOrders) {
      updateOrderState(cancelledOrder, OrderState.CANCELLED, true);
      log.info("handleCancelAllLongOpeningGeneralOrders called from class " + Thread.currentThread().getStackTrace()[2]
          + "Cancelled Long Opening Trigger Order: " + cancelledOrder);
      if (cancelledOrder.getParentFill() != null)
        handleCancelAllTriggerOrdersByParentFill(cancelledOrder.getParentFill());

    }

    //                if(parentKey instanceof Fill) 
    //                      parentFill = (Fill) parentKey;
    //                if(parentKey instanceof Order) 
    //                     parentOrder = (Order) parentKey;
    //                if ((parentFill!=null && parentFill.getMarket().equals(market)) || (parentOrder!=null && parentOrder.getMarket().equals(market)))
    //                    triggerOrders.remove(parentKey);

    //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill

    return cancelledOrders;
    // triggerOrderLock.unlock();
    // }

  }

  @Override
  public Collection<Order> handleCancelAllShortOpeningGeneralOrders(Portfolio portfolio, Market market) {
    Collection<Order> cancelledOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return cancelledOrders;
    //synchronized (lock) {
    // triggerOrderLock.lock();
    log.debug(this.getClass().getSimpleName() + " : handleCancelAllShortOpeningGeneralOrders to called from stack "
        + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return cancelledOrders;

    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        return cancelledOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator(); it.hasNext();) {
          Order triggerOrder = it.next();

          if (triggerOrder.getMarket().equals(market) && triggerOrder.isAsk() && triggerOrder.getPositionEffect() == (PositionEffect.OPEN))

            cancelledOrders.add(triggerOrder);

          //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill
          //   if (triggerOrder.getParentFill() != null)
          //     triggerOrder.getParentFill().setStopPriceCount(0);
        }
      }
      //  synchronized (triggerOrders.get(parentKey)) {

      if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
          && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null)
        synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(cancelledOrders);
        }
      if (triggerOrders != null && triggerOrders.get(market) != null
          && triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null
          && !triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(cancelledOrders);
        }
    }
    for (Order cancelledOrder : cancelledOrders) {
      updateOrderState(cancelledOrder, OrderState.CANCELLED, true);
      log.info("handleCancelAllShortOpeningGeneralOrders called from class " + Thread.currentThread().getStackTrace()[2]
          + ": Cancelled Short Opening Trigger Order: " + cancelledOrder);
      if (cancelledOrder.getParentFill() != null)
        handleCancelAllTriggerOrdersByParentFill(cancelledOrder.getParentFill());
    }

    //                if(parentKey instanceof Fill) 
    //                      parentFill = (Fill) parentKey;
    //                if(parentKey instanceof Order) 
    //                     parentOrder = (Order) parentKey;
    //                if ((parentFill!=null && parentFill.getMarket().equals(market)) || (parentOrder!=null && parentOrder.getMarket().equals(market)))
    //                    triggerOrders.remove(parentKey);

    //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill

    return cancelledOrders;
    // triggerOrderLock.unlock();
    // }

  }

  @Override
  public Collection<Order> handleCancelAllShortOpeningGeneralOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<Order> cancelledOrders = new ConcurrentLinkedQueue<>();
    if (market == null || portfolio == null)
      return cancelledOrders;

    //synchronized (lock) {
    // triggerOrderLock.lock();
    log.debug(this.getClass().getSimpleName() + " : handleCancelAllShortOpeningGeneralOrders to called from stack "
        + Thread.currentThread().getStackTrace()[2]);
    if (portfolio == null || market == null || triggerOrders == null || triggerOrders.isEmpty() || triggerOrders.get(market) == null
        || triggerOrders.get(market).isEmpty())
      return cancelledOrders;

    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();
      if (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) == null
          || triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        return cancelledOrders;
      synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
        for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).iterator(); it.hasNext();) {
          Order triggerOrder = it.next();

          if (triggerOrder.getOrderGroup() == orderGroup && triggerOrder.getMarket().equals(market) && triggerOrder.isAsk()
              && triggerOrder.getPositionEffect() == (PositionEffect.OPEN))

            cancelledOrders.add(triggerOrder);

          //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill
          //   if (triggerOrder.getParentFill() != null)
          //     triggerOrder.getParentFill().setStopPriceCount(0);
        }
      }
      //  synchronized (triggerOrders.get(parentKey)) {
      if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
          && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null)
        synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(cancelledOrders);
        }
      if (triggerOrders != null && triggerOrders.get(market) != null
          && triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null
          && !triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).isEmpty())
        synchronized (triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL)) {
          triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL).removeAll(cancelledOrders);
        }

    }
    for (Order cancelledOrder : cancelledOrders) {
      updateOrderState(cancelledOrder, OrderState.CANCELLED, true);
      log.info("handleCancelAllShortOpeningGeneralOrders called from class " + Thread.currentThread().getStackTrace()[2]
          + ": Cancelled Short Opening Trigger Order: " + cancelledOrder);
      if (cancelledOrder.getParentFill() != null)
        handleCancelAllTriggerOrdersByParentFill(cancelledOrder.getParentFill());
    }

    //                if(parentKey instanceof Fill) 
    //                      parentFill = (Fill) parentKey;
    //                if(parentKey instanceof Order) 
    //                     parentOrder = (Order) parentKey;
    //                if ((parentFill!=null && parentFill.getMarket().equals(market)) || (parentOrder!=null && parentOrder.getMarket().equals(market)))
    //                    triggerOrders.remove(parentKey);

    //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill

    return cancelledOrders;
    // triggerOrderLock.unlock();
    // }

  }

  @Override
  public Order getPendingTriggerOrder(Order order) {
    //synchronized (lock) {
    log.debug(this.getClass().getSimpleName() + " : getPendingTriggerOrder to called from stack " + Thread.currentThread().getStackTrace()[2]);
    for (Iterator<Tradeable> itm = triggerOrders.keySet().iterator(); itm.hasNext();) {
      Tradeable market = itm.next();
      for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
        Double triggerInterval = itd.next();

        for (Iterator<TransactionType> ittt = triggerOrders.get(market).get(triggerInterval).keySet().iterator(); ittt.hasNext();) {
          TransactionType transactionType = ittt.next();
          synchronized (triggerOrders.get(market).get(triggerInterval).get(transactionType)) {
            for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(transactionType).iterator(); it.hasNext();) {
              Order triggerOrder = it.next();

              if (triggerOrder.equals(order))
                return triggerOrder;

              //TODO could be a condition here where we have not removed the trigger order but we have set the stop price to 0 on the fill

            }
          }

        }
      }

    }

    return null;

  }

  @Override
  public Collection<SpecificOrder> handleCancelAllShortOpeningSpecificOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    if (market == null || portfolio == null)
      return orderToCancel;

    //  synchronized (lock) {
    for (Order order : getPendingOrders()) {
      if (order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen() && specificOrder.getMarket().equals(market)
            && specificOrder.getPositionEffect() == (PositionEffect.OPEN) && specificOrder.isAsk()) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllShortOpeningSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2]
              + " cancelling order : " + specificOrder);
        }

      }
    }

    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);

    return cancelSpecificOrder(cancelledOrders);

  }

  @Override
  public Collection<SpecificOrder> handleCancelAllShortOpeningSpecificOrders(Portfolio portfolio, Market market, double orderGroup) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    //  synchronized (lock) {
    for (Order order : getPendingOrders()) {
      if (order.getOrderGroup() == orderGroup && order instanceof SpecificOrder) {
        SpecificOrder specificOrder = (SpecificOrder) order;
        if (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen() && specificOrder.getMarket().equals(market)
            && specificOrder.getPositionEffect() == (PositionEffect.OPEN) && specificOrder.isAsk()) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllShortOpeningSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2]
              + " cancelling order : " + specificOrder);
        }

      }
    }

    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);

    return cancelSpecificOrder(cancelledOrders);

  }

  @Override
  public Collection<SpecificOrder> handleCancelAllOpeningSpecificOrders(Portfolio portfolio, Market market) {
    Collection<SpecificOrder> orderToCancel = new ArrayList<>();
    if (market == null || portfolio == null)
      return orderToCancel;

    //  synchronized (lock) {
    for (Order order : getPendingOrders()) {
      SpecificOrder specificOrder;
      if (order instanceof SpecificOrder) {
        specificOrder = (SpecificOrder) order;

        if (orderStateMap.get(specificOrder) != null && orderStateMap.get(specificOrder).isOpen() && specificOrder.getMarket().equals(market)
            && specificOrder.getPositionEffect() == (PositionEffect.OPEN)) {
          orderToCancel.add(specificOrder);
          log.info("handleCancelAllOpeningSpecificOrders called from class " + Thread.currentThread().getStackTrace()[2] + " cancelling order : "
              + specificOrder);
        }

      }
    }

    Collection<SpecificOrder> cancelledOrders = cancelSpecificOrder(orderToCancel);
    return cancelSpecificOrder(cancelledOrders);

  }

  @Override
  public Collection<SpecificOrder> cancelSpecificOrder(Collection<SpecificOrder> orders) {
    log.trace("cancelSpecificOrder: called from class " + Thread.currentThread().getStackTrace()[2]);
    Collection<SpecificOrder> cancelledOrders = new ArrayList<SpecificOrder>();
    for (SpecificOrder order : orders) {
      try {
        if (orderStateMap.get(order) != null && orderStateMap.get(order).isOpen() && handleCancelSpecificOrder(order))
          cancelledOrders.add(order);
      } catch (Throwable e) {
        // TODO Auto-generated catch block
        log.error("cancelSpecificOrder: called from class " + Thread.currentThread().getStackTrace()[2] + " unable to cancel order:" + order, e);
      }
    }
    return cancelledOrders;

  }

  public void updateOrderState(Order order, OrderState state, boolean route) {
    log.debug(this.getClass().getSimpleName() + " - updateOrderState: called from class " + Thread.currentThread().getStackTrace()[2]);
    // need to add vaildation here on state and last state
    synchronized (order) {
      OrderState oldState = null;
      if (order != null) {
        oldState = orderStateMap.get(order);
        // order.merge();
      }

      if (oldState != null && (oldState.equals(state) || oldState.compareTo(state) > 0)) {
        log.info(this.getClass().getSimpleName() + ": updateOrderState. Ealier state " + state + " procssed after " + oldState + " for order "
            + order);
        return;
      }
      if (oldState == null) {
        oldState = OrderState.NEW;
        order.persit();
      } else if (oldState != null && order != null && !stateOrderMap.get(oldState).isEmpty()) {
        if (stateOrderMap.get(oldState).remove(order))
          log.info(this.getClass().getSimpleName() + ":UpdateOrderState - Removed order " + order + "with state " + oldState + " from stateOrderMap");

      }
      if (order != null) {
        orderStateMap.put(order, state);
        log.info(order + "/" + System.identityHashCode(order) + " with state " + state + " added to orderStateMap as "
            + orderStateMap.get(order).toString());

        if (stateOrderMap.get(state) == null) {
          Set<Order> orderSet = new HashSet<Order>();
          // orderSet.add(order);
          stateOrderMap.put(state, orderSet);

        }
        if (stateOrderMap.get(state).add(order))
          log.info(order + " with state " + state + " added to stateOrderMap as " + orderStateMap.get(order).toString());
      }

      // this.getClass()
      // context.route(new OrderUpdate(order, oldState, state));
      OrderUpdate orderUpdate = orderUpdateFactory.create(context.getTime(), order, oldState, state);
      //if (route)
      context.setPublishTime(orderUpdate);

      orderUpdate.persit();
      handleOrderUpdate(orderUpdate);
      log.debug(this.getClass().getSimpleName() + " - updateOrderState: published orderupdate " + orderUpdate.getId() + " for order " + order
          + " with state " + state + " after added to stateOrderMap with " + orderStateMap.get(order).toString());

      context.route(orderUpdate);
      //else
      //  context.publish(orderUpdate);
      //  fter added to stateOrderMap 

      // so we place a trigger order, then we create a child order, which then get's cancelled
      // and is replaced by a new child order, 
      // we triggered it it got routed, then we cancelled the child
      // if we cancell the parent, it will not longer be routed, but it won't any way.

      if (order != null && order.getParentOrder() != null && oldState.compareTo(state) > 0)

        //  && (!state.isCancelled() || !state.isCancelling() || !state.isExpired()))
        updateParentOrderState(order.getParentOrder(), order, state);
    }
  }

  private void updateParentOrderState(Order order, Order childOrder, OrderState childOrderState) {
    //    if (order.getFillType() == FillType.ONE_CANCELS_OTHER)
    //       return;
    OrderState oldState = orderStateMap.get(order);
    if (oldState.equals(OrderState.ROUTED))
      log.debug("test");
    // certain states we don't want ot properage to parent orders
    //    if (childOrderState.isCancelled() || childOrderState.isCancelling() || childOrderState.isExpired()) {
    //      log.warn(this.getClass().getSimpleName() + " : updateParentOrderState. Updateing parent order " + order.getId() + " from oldState " + oldState
    //          + "  to  childOrderState " + "for " + childOrderState + " Not permitted. Called from stack " + Thread.currentThread().getStackTrace()[2]);
    //    return;

    // }

    switch (childOrderState) {
      case NEW:
        boolean fullyNew = true;
        for (Order child : order.getOrderChildren()) {
          if (orderStateMap.get(child) != null && !orderStateMap.get(child).isNew()) {
            fullyNew = false;
            break;
          }
        }
        if (fullyNew)
          updateOrderState(order, OrderState.NEW, true);
        // }
        break;

      case TRIGGER:
        boolean fullyTrigger = true;
        for (Order child : order.getOrderChildren()) {
          if (orderStateMap.get(child) != null && !orderStateMap.get(child).isTrigger()) {
            fullyTrigger = false;
            break;
          }
        }
        if (fullyTrigger)
          updateOrderState(order, OrderState.TRIGGER, true);
        // }
        break;

      case ROUTED:
        //TODO: update state once all children have same state
        boolean fullyRouted = true;
        for (Order child : order.getOrderChildren()) {
          if (orderStateMap.get(child) != null && !orderStateMap.get(child).isRouted()) {
            fullyRouted = false;
            break;
          }
        }
        if (fullyRouted)
          updateOrderState(order, OrderState.ROUTED, true);
        // }
        break;
      case PLACED:
        //TODO: update state once all children have same state
        boolean fullyPlaced = true;
        for (Order child : order.getOrderChildren()) {
          if (orderStateMap.get(child) != null && !orderStateMap.get(child).isPlaced()) {
            fullyPlaced = false;
            break;
          }
        }
        if (fullyPlaced)
          updateOrderState(order, OrderState.PLACED, true);
        // }
        break;
      case PARTFILLED:
        boolean fullyPartFilled = false;
        for (Order child : order.getOrderChildren()) {
          if (orderStateMap.get(child).isPartfilled()) {
            fullyPartFilled = true;
            break;
          }
        }
        if (fullyPartFilled)
          updateOrderState(order, OrderState.PARTFILLED, true);

        break;

      case FILLED:
        //if (oldState == OrderState.CANCELLING) {
        boolean fullyFilled = true;
        for (Order child : order.getOrderChildren()) {
          if (orderStateMap.get(child) != null && orderStateMap.get(child).isOpen()) {
            fullyFilled = false;
            updateOrderState(order, OrderState.PARTFILLED, true);
            break;
          }
        }
        if (fullyFilled)
          updateOrderState(order, OrderState.FILLED, true);

        break;

      case CANCELLING:
        boolean fullyCancelling = true;
        for (Order child : order.getOrderChildren()) {
          if (orderStateMap.get(child) != null && !orderStateMap.get(child).isCancelled()) {
            fullyCancelling = false;
            break;
          }
        }
        if (fullyCancelling)
          updateOrderState(order, OrderState.CANCELLING, true);
        // }
        break;
      case CANCELLED:
        //  if (oldState == OrderState.CANCELLING) {
        boolean fullyCancelled = true;
        for (Order child : order.getOrderChildren()) {
          if (orderStateMap.get(child) != null && !orderStateMap.get(child).isCancelled()) {
            fullyCancelled = false;
            break;
          }
        }
        if (fullyCancelled)
          updateOrderState(order, OrderState.CANCELLED, true);
        // }
        break;
      case REJECTED:
        boolean fullyRejected = true;
        for (Order child : order.getOrderChildren()) {
          if (orderStateMap.get(child) != null && !orderStateMap.get(child).isRejected()) {
            fullyRejected = false;
            break;
          }
        }
        if (fullyRejected) {
          updateOrderState(order, OrderState.REJECTED, true);
          reject(order, "Child order was rejected");
        }
        // }
        break;

      case EXPIRED:
        if (childOrder != null && childOrder.getExpiration() != null && !childOrder.getExpiration().isEqual(order.getExpiration()))
          throw new Error("Child order expirations must match parent order expirations");
        boolean fullyExpired = true;
        for (Order child : order.getOrderChildren()) {
          if (orderStateMap.get(child) != null && !orderStateMap.get(child).isExpired()) {
            fullyExpired = false;
            break;
          }
        }
        if (fullyExpired)
          updateOrderState(order, OrderState.EXPIRED, true);
        // }
        break;

      default:
        log.warn("Unknown order state: " + childOrderState);
        break;
    }
  }

  /*
   * protected static final void persitOrderFill(Event event) { if (event instanceof Order) { Order order = (Order) event; order.persit(); // Order
   * duplicate = PersistUtil.queryZeroOne(Order.class, "select o from Order o where o=?1", order); // if (duplicate == null) //
   * PersistUtil.insert(order); // else // PersistUtil.merge(order); } else if (event instanceof Fill) { Fill fill = (Fill) event; // //
   * fill.persit(); // Fill duplicate = PersistUtil.queryZeroOne(Fill.class, "select f from Fill f where f=?1", fill); // if (duplicate == null) // fi
   * // PersistUtil.insert(fill); // else // PersistUtil.merge(fill); } // else { // if not a Trade, persist unconditionally // try { //
   * PersistUtil.insert(event); //} catch (Throwable e) { // throw new Error("Could not insert " + event, e); // } //} }
   */
  private void removeTriggerOrder(Order order) {
    log.debug(this.getClass().getSimpleName() + " : removeTriggerOrder to called from stack " + Thread.currentThread().getStackTrace()[2]);
    List<Order> removedTriggerOrders = new ArrayList<Order>();

    for (Iterator<Tradeable> itm = triggerOrders.keySet().iterator(); itm.hasNext();) {
      Tradeable market = itm.next();
      for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
        Double triggerInterval = itd.next();

        for (Iterator<TransactionType> ittt = triggerOrders.get(market).get(triggerInterval).keySet().iterator(); ittt.hasNext();) {
          TransactionType transactionType = ittt.next();
          synchronized (triggerOrders.get(market).get(triggerInterval).get(transactionType)) {

            for (Iterator<Order> it = triggerOrders.get(market).get(triggerInterval).get(transactionType).iterator(); it.hasNext();) {

              Order triggerOrder = it.next();

              if (triggerOrder.equals(order)) {
                removedTriggerOrders.add(order);

                //  triggerOrders.get(parentKey).remove(order);
                // --removeOrder(order);
              }

              // pendingOrders.remove(order);
            }
          }
          //  synchronized (triggerOrders.get(parentKey)) {

          if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
              && trailingTriggerOrders.get(market).get(triggerInterval).get(transactionType) != null)
            synchronized (trailingTriggerOrders.get(market).get(triggerInterval).get(transactionType)) {
              trailingTriggerOrders.get(market).get(triggerInterval).get(transactionType).removeAll(removedTriggerOrders);
            }
          synchronized (triggerOrders.get(market).get(triggerInterval).get(transactionType)) {
            triggerOrders.get(market).get(triggerInterval).get(transactionType).removeAll(removedTriggerOrders);
          }
          //;

          //     }

        }
      }

    }

  }

  //(triggerOrder.isBid() ? ascendingStopPriceComparator
  //   : descendingStopPriceComparator);
  @Override
  public synchronized void sortLongStopOrders(Tradeable market) {
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();

      sortOrders(triggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL), TransactionType.SELL, market,
          descendingStopPriceComparator);
      if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
          && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL) != null)
        sortOrders(trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.SELL), TransactionType.SELL, market,
            ascendingTrailingStopPriceComparator);
    }

  }

  @Override
  public synchronized void sortShortStopOrders(Tradeable market) {
    for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
      Double triggerInterval = itd.next();

      sortOrders(triggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY), TransactionType.BUY, market, ascendingStopPriceComparator);
      if (trailingTriggerOrders != null && trailingTriggerOrders.get(market) != null
          && trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY) != null)
        sortOrders(trailingTriggerOrders.get(market).get(triggerInterval).get(TransactionType.BUY), TransactionType.BUY, market,
            descendingTrailingStopPriceComparator);
    }

    //      Comparator<Order> comparator = (transactionType == TransactionType.BUY) ? ascendingStopPriceComparator : descendingStopPriceComparator;
    //    asdfasdf  Collections.sort(orders, comparator);

    // triggerOrdersTable.get(market).put(transactionType,triggerTable);

    //      (triggerOrder.isBid() ? ascendingStopPriceComparator
    //   : descendingStopPriceComparator);

  }

  public void sortOrders(List<Order> orders, TransactionType transactionType, Tradeable market, Comparator<Order> comparator) {

    //      Comparator<Order> comparator = (transactionType == TransactionType.BUY) ? ascendingStopPriceComparator : descendingStopPriceComparator;
    synchronized (orders) {
      Collections.sort(orders, comparator);
    }

    // triggerOrdersTable.get(market).put(transactionType,triggerTable);

    //      (triggerOrder.isBid() ? ascendingStopPriceComparator
    //   : descendingStopPriceComparator);

  }

  private synchronized void addTriggerOrder(Order triggerOrder) {
    //If the trigger order is from a fill, we use the fill as the key for mutliple triggers, else we use the parent
    //any one of the multiple triggers can trigger first, but once one is triggered, all others are removed at for either the same fill or same parent
    log.debug(this.getClass().getSimpleName() + " : addTriggerOrder to called from stack " + Thread.currentThread().getStackTrace()[2]);
    Event eventKey = (triggerOrder.getParentFill() != null) ? triggerOrder.getParentFill() : triggerOrder.getParentOrder();
    Market market = (triggerOrder.getParentFill() != null) ? triggerOrder.getParentFill().getMarket() : triggerOrder.getMarket();

    if (eventKey == null)
      eventKey = triggerOrder;
    // synchronized (lock) {
    // buy orders sorted lowest to highest (ascending)
    // sell order sorted highest to lowest (descending)
    // bid order are to exit a short posiont so we want the list that is highest to loser (descnding)
    // Comparator<Order> comparator = (transactionType == TransactionType.BUY) ? ascendingStopPriceComparator : descendingStopPriceComparator;

    List<Order> triggerOrderQueue = new ArrayList<Order>();
    List<Order> trailingTriggerOrderQueue = new ArrayList<Order>();
    // if (triggerOrder.isBid())
    //   triggerTable = TreeBasedTable.create(ascendingStopPriceComparator, descendingTrailingStopPriceComparator);

    //  Table<Order, Order, Order> triggerTable;

    // Comparator<? super String> rowComparator;

    //(triggerOrder.isBid() ? ascendingStopPriceComparator
    //   : descendingStopPriceComparator);

    // buy orders have lowest price first, so if the price < first by order we break

    //   ConcurrentLinkedQueue<Order> triggerOrderQueue = new ConcurrentLinkedQueue<Order>();
    // We want buy orders sorted  loweset to highest (ascending) and sell orders sorted to highest to lowest (decending)
    TransactionType transactionType = (triggerOrder.isBid()) ? TransactionType.BUY : TransactionType.SELL;
    //  TreeBasedTable<String, Integer, Character> table =
    //        TreeBasedTable.create(rowComparator, columnComparator);
    if (triggerOrder.getFillType().equals(FillType.TRAILING_STOP_LIMIT) || triggerOrder.getFillType().equals(FillType.TRAILING_STOP_LOSS)
        || triggerOrder.getFillType().equals(FillType.REENTRANT_TRAILING_STOP_LOSS)
        || triggerOrder.getFillType().equals(FillType.REENTRANT_TRAILING_STOP_LIMIT)
        && (triggerOrder.getStopPrice() != null && triggerOrder.getStopAmount() != null)) {
      if (trailingTriggerOrders.get(market) == null || trailingTriggerOrders.get(market).isEmpty()) {
        trailingTriggerOrderQueue.add(triggerOrder);

        ConcurrentHashMap<TransactionType, List<Order>> transactionTypeTriggerOrders = new ConcurrentHashMap<TransactionType, List<Order>>();
        transactionTypeTriggerOrders.put(transactionType, trailingTriggerOrderQueue);
        ConcurrentHashMap<Double, ConcurrentHashMap<TransactionType, List<Order>>> triggerIntervalTriggerOrders = new ConcurrentHashMap<Double, ConcurrentHashMap<TransactionType, List<Order>>>();
        triggerIntervalTriggerOrders.put(triggerOrder.getTriggerInterval(), transactionTypeTriggerOrders);

        trailingTriggerOrders.put(market, triggerIntervalTriggerOrders);
        sortOrders(trailingTriggerOrderQueue, transactionType, market,
            (transactionType == TransactionType.BUY) ? descendingTrailingStopPriceComparator : ascendingTrailingStopPriceComparator);
        //               printTable(triggerOrdersTable.get(market).get(transactionType));

      } else if (trailingTriggerOrders.get(market).get(triggerOrder.getTriggerInterval()) == null
          || trailingTriggerOrders.get(market).get(triggerOrder.getTriggerInterval()).isEmpty()) {

        trailingTriggerOrderQueue.add(triggerOrder);

        ConcurrentHashMap<TransactionType, List<Order>> transactionTypeTriggerOrders = new ConcurrentHashMap<TransactionType, List<Order>>();
        transactionTypeTriggerOrders.put(transactionType, trailingTriggerOrderQueue);
        // so if the hash map is there, we just add our interval to it.
        trailingTriggerOrders.get(market).put(triggerOrder.getTriggerInterval(), transactionTypeTriggerOrders);

        sortOrders(trailingTriggerOrderQueue, transactionType, market,
            (transactionType == TransactionType.BUY) ? descendingTrailingStopPriceComparator : ascendingTrailingStopPriceComparator);

      }

      else if (trailingTriggerOrders.get(market).get(triggerOrder.getTriggerInterval()).get(transactionType) == null
          || trailingTriggerOrders.get(market).get(triggerOrder.getTriggerInterval()).get(transactionType).isEmpty()) {
        trailingTriggerOrderQueue.add(triggerOrder);
        trailingTriggerOrders.get(market).get(triggerOrder.getTriggerInterval()).put(transactionType, trailingTriggerOrderQueue);
        sortOrders(trailingTriggerOrderQueue, transactionType, market,
            (transactionType == TransactionType.BUY) ? descendingTrailingStopPriceComparator : ascendingTrailingStopPriceComparator);

      } else {
        synchronized (trailingTriggerOrders.get(market).get(triggerOrder.getTriggerInterval()).get(transactionType)) {
          trailingTriggerOrders.get(market).get(triggerOrder.getTriggerInterval()).get(transactionType).add(triggerOrder);
        }
        sortOrders(trailingTriggerOrders.get(market).get(triggerOrder.getTriggerInterval()).get(transactionType), transactionType, market,
            (transactionType == TransactionType.BUY) ? descendingTrailingStopPriceComparator : ascendingTrailingStopPriceComparator);
        log.trace("Row Stop Price all tailing trigger orders" + trailingTriggerOrders.get(market).get(transactionType));

      }

    }
    if (triggerOrders.get(market) == null || triggerOrders.get(market).isEmpty()) {
      triggerOrderQueue.add(triggerOrder);

      ConcurrentHashMap<TransactionType, List<Order>> transactionTypeTriggerOrders = new ConcurrentHashMap<TransactionType, List<Order>>();
      transactionTypeTriggerOrders.put(transactionType, triggerOrderQueue);
      ConcurrentHashMap<Double, ConcurrentHashMap<TransactionType, List<Order>>> triggerIntervalTriggerOrders = new ConcurrentHashMap<Double, ConcurrentHashMap<TransactionType, List<Order>>>();
      triggerIntervalTriggerOrders.put(triggerOrder.getTriggerInterval(), transactionTypeTriggerOrders);

      triggerOrders.put(market, triggerIntervalTriggerOrders);
      sortOrders(triggerOrderQueue, transactionType, market, (transactionType == TransactionType.BUY) ? ascendingStopPriceComparator
          : descendingStopPriceComparator);
      //               printTable(triggerOrdersTable.get(market).get(transactionType));
      return;
    } else if (triggerOrders.get(market).get(triggerOrder.getTriggerInterval()) == null
        || triggerOrders.get(market).get(triggerOrder.getTriggerInterval()).isEmpty()) {

      triggerOrderQueue.add(triggerOrder);

      ConcurrentHashMap<TransactionType, List<Order>> transactionTypeTriggerOrders = new ConcurrentHashMap<TransactionType, List<Order>>();
      transactionTypeTriggerOrders.put(transactionType, triggerOrderQueue);
      // so if the hash map is there, we just add our interval to it.
      triggerOrders.get(market).put(triggerOrder.getTriggerInterval(), transactionTypeTriggerOrders);

      sortOrders(triggerOrderQueue, transactionType, market, (transactionType == TransactionType.BUY) ? ascendingStopPriceComparator
          : descendingStopPriceComparator);

    } else if (triggerOrders.get(market).get(triggerOrder.getTriggerInterval()).get(transactionType) == null
        || triggerOrders.get(market).get(triggerOrder.getTriggerInterval()).get(transactionType).isEmpty()) {
      triggerOrderQueue.add(triggerOrder);

      triggerOrders.get(market).get(triggerOrder.getTriggerInterval()).put(transactionType, triggerOrderQueue);
      sortOrders(triggerOrderQueue, transactionType, market, (transactionType == TransactionType.BUY) ? ascendingStopPriceComparator
          : descendingStopPriceComparator);

      return;

    } else {
      synchronized (triggerOrders.get(market).get(triggerOrder.getTriggerInterval()).get(transactionType)) {
        triggerOrders.get(market).get(triggerOrder.getTriggerInterval()).get(transactionType).add(triggerOrder);
      }
      sortOrders(triggerOrders.get(market).get(triggerOrder.getTriggerInterval()).get(transactionType), transactionType, market,
          (transactionType == TransactionType.BUY) ? ascendingStopPriceComparator : descendingStopPriceComparator);
      log.trace("Row Stop Price all trigger orders" + triggerOrders.get(market).get(triggerOrder.getTriggerInterval()).get(transactionType));
      return;
    }

  }

  protected void printTable(Table<Order, Order, Order> table) {
    //  Map<Order, Map<Order, Order>> row;

    //sell rows are ascending
    //sell cols are dcesnding

    for (Order row : table.rowKeySet()) {
      log.trace("At " + context.getTime() + (row.isBid() ? " Buy" : " Sell") + " Row Stop Price:" + row.getStopPrice());
    }

    for (Order col : table.columnKeySet()) {
      log.trace("At " + context.getTime() + (col.isBid() ? " Buy" : " Sell") + " Column Stop Amount:"

      + (col.isBid() ? (col.getStopPrice().minus(col.getStopAmount())) : (col.getStopPrice().plus(col.getStopAmount()))));
    }
    /*
     * for (Entry<Order, Order> entry : table.row(row).entrySet()) { log.debug("At " + context.getTime() + " Row Stop Price:" + row.getStopPrice();
     * for (Order row : table.rowKeySet()) { for (Entry<Order, Order> entry : table.row(row).entrySet()) { log.debug("At " + context.getTime() +
     * " Row Stop Price:" + row.getStopPrice() + " Column Stop Amount:" + (entry.getValue().isBid() ?
     * (entry.getKey().getStopPrice().minus(entry.getKey().getStopAmount())) : (entry.getKey().getStopPrice() .plus(entry.getKey().getStopAmount())))
     * + (entry.getValue().isBid() ? " Buy " : " Sell ") + "OrderID:" + entry.getValue().getId() + " Stop Price " + entry.getValue().getStopPrice() +
     * " Stop Amount " + entry.getValue().getStopAmount()); } }
     */// lets itterate over teh columns in the row.
       //       for (Order col : table.row) {
       // table.remove(rowKey, columnKey)
    for (Cell<Order, Order, Order> cell : table.cellSet()) {
      //  log.debug(
      log.trace("At "
          + context.getTime()
          + " Row Stop Price:"
          + cell.getRowKey().getStopPrice()
          + " Column Stop Amount:"
          + (cell.getValue().isBid() ? (cell.getColumnKey().getStopPrice().minus(cell.getColumnKey().getStopAmount())) : (cell.getColumnKey()
              .getStopPrice().plus(cell.getColumnKey().getStopAmount()))) + (cell.getValue().isBid() ? " Buy " : " Sell ") + "OrderID:"
          + cell.getValue().getId() + " Stop Price " + cell.getValue().getStopPrice() + " Stop Amount " + cell.getValue().getStopAmount());
    }
  }

  protected final void CreateTransaction(EntityBase entity, Boolean route) {
    Transaction transaction = null;
    Order order;
    switch (entity.getClass().getSimpleName()) {

      case "SpecificOrder":
        order = (Order) entity;
        try {
          transaction = transactionFactory.create(order, context.getTime());
          context.setPublishTime(transaction);

          transaction.persit();
          // PersistUtil.insert(transaction);
          log.info("Created new transaction " + transaction);
          // if (route)
          context.route(transaction);
          // else
          // context.publish(transaction);

        } catch (Exception e1) {
          log.error("Threw a Execption, full stack trace follows:", e1);

          // TODO Auto-generated catch block
          e1.printStackTrace();
        }

        break;
      case "Fill":
        Fill fill = (Fill) entity;
        try {
          transaction = transactionFactory.create(fill, context.getTime());
          context.setPublishTime(transaction);

          transaction.persit();
          log.info("Created new transaction " + transaction);
          // if (route)
          context.route(transaction);
          //else
          //context.publish(transaction);

        } catch (Exception e) {
          // TODO Auto-generated catch block
          log.error("Threw a Execption, full stack trace follows:", e);

          e.printStackTrace();
        }
        break;

    }

  }

  // rounding depends on whether it is a buy or sell order.  we round to the "best" price
  private static final RemainderHandler sellHandler = new RemainderHandler() {
    @Override
    public RoundingMode getRoundingMode() {
      return RoundingMode.CEILING;
    }
  };

  private static final RemainderHandler buyHandler = new RemainderHandler() {
    @Override
    public RoundingMode getRoundingMode() {
      return RoundingMode.FLOOR;
    }
  };

  @Transient
  public PortfolioService getPortfolioService() {
    return portfolioService;
  }

  protected void setQuotes(QuoteService quotes) {
    this.quotes = quotes;
  }

  @Transient
  public QuoteService getQuotes() {
    return quotes;
  }

  @Transient
  public ConcurrentLinkedQueue<Order> getTriggerOrders() {
    //   ConcurrentHashMap<Event, ConcurrentLinkedQueue<Order>> triggerOrderMap = new ConcurrentHashMap<Event, ConcurrentLinkedQueue<Order>>();
    ConcurrentLinkedQueue<Order> triggerOrderQueue = new ConcurrentLinkedQueue<Order>();
    for (Iterator<Tradeable> itm = triggerOrders.keySet().iterator(); itm.hasNext();) {
      Tradeable market = itm.next();
      for (Iterator<Double> itd = triggerOrders.get(market).keySet().iterator(); itd.hasNext();) {
        Double triggerInterval = itd.next();
        //synchronized()
        for (Iterator<TransactionType> ittt = triggerOrders.get(market).get(triggerInterval).keySet().iterator(); ittt.hasNext();) {
          TransactionType transactionType = ittt.next();
          synchronized (triggerOrders.get(market).get(triggerInterval).get(transactionType)) {
            triggerOrderQueue.addAll(triggerOrders.get(market).get(triggerInterval).get(transactionType));
          }

        }
      }
    }

    //
    return triggerOrderQueue;
  }

  @Override
  public Map<Order, OrderState> getOrderStateMap() {
    return orderStateMap;
  }

  protected void setPortfolioService(PortfolioService portfolioService) {
    this.portfolioService = portfolioService;
  }

  public BaseOrderService() {
  }

  private static ExecutorService service;

  private static CountDownLatch fillProcessingLatch = null;

  @Inject
  protected Context context;

  protected static Logger log = LoggerFactory.getLogger("org.cryptocoinpartners.orderService");

  protected boolean enableTrading = false;
  protected final Map<Order, OrderState> orderStateMap = new ConcurrentHashMap<Order, OrderState>();

  protected final Map<OrderState, Set<Order>> stateOrderMap = new ConcurrentHashMap<OrderState, Set<Order>>();
  @Inject
  protected transient QuoteService quotes;
  @Inject
  protected transient PortfolioService portfolioService;

  @Inject
  protected transient GeneralOrderFactory generalOrderFactory;

  @Inject
  protected transient SpecificOrderFactory specificOrderFactory;

  @Inject
  protected transient OrderUpdateFactory orderUpdateFactory;

  @Inject
  protected transient TransactionFactory transactionFactory;

  @Inject
  protected transient BookFactory bookFactory;

  @Inject
  protected transient FillFactory fillFactory;

}
