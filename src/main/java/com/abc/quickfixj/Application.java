package com.abc.quickfixj;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.Security;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.core.status.StatusUtil;
import quickfix.ConfigError;
import quickfix.DataDictionaryProvider;
import quickfix.DoNotSend;
import quickfix.FieldConvertError;
import quickfix.FieldNotFound;
import quickfix.FixVersions;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.LogUtil;
import quickfix.Message;
import quickfix.MessageUtils;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.UnsupportedMessageType;
import quickfix.field.ApplVerID;
import quickfix.field.AvgPx;
import quickfix.field.CumQty;
import quickfix.field.ExecAckStatus;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.NewSeqNo;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.SecurityID;
import quickfix.field.SecurityRequestResult;
import quickfix.field.SecurityResponseType;
import quickfix.field.SecurityStatus;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TradeReportID;
import quickfix.field.TradeReportTransType;
import quickfix.field.TradeRequestID;
import quickfix.field.TradeRequestType;
import quickfix.field.TransactTime;
import quickfix.field.TrdRptStatus;
import quickfix.fix50.NewOrderSingle;
import quickfix.fix50sp1.SecurityListRequest;
import quickfix.fix50sp2.DerivativeSecurityListUpdateReport;
import quickfix.fix50sp2.SecurityDefinition;
import quickfix.fix50sp2.SecurityDefinitionUpdateReport;
import quickfix.fix50sp2.SecurityListUpdateReport;
import quickfix.fix50sp2.SecurityStatusRequest;
import quickfix.fix50sp2.TradeCaptureReportAck;

public class Application extends quickfix.MessageCracker implements quickfix.Application {
	private static final String DEFAULT_MARKET_PRICE_KEY = "DefaultMarketPrice";
	private static final String ALWAYS_FILL_LIMIT_KEY = "AlwaysFillLimitOrders";
	private static final String VALID_ORDER_TYPES_KEY = "ValidOrderTypes";

	private final Logger log = LoggerFactory.getLogger(getClass());
	private final boolean alwaysFillLimitOrders;
	private final HashSet<String> validOrderTypes = new HashSet<>();
	private MarketDataProvider marketDataProvider;

	public Application(SessionSettings settings) throws ConfigError, FieldConvertError {
		initializeValidOrderTypes(settings);
		initializeMarketDataProvider(settings);

		alwaysFillLimitOrders = settings.isSetting(ALWAYS_FILL_LIMIT_KEY) && settings.getBool(ALWAYS_FILL_LIMIT_KEY);
	}

	private void initializeMarketDataProvider(SessionSettings settings) throws ConfigError, FieldConvertError {
		if (settings.isSetting(DEFAULT_MARKET_PRICE_KEY)) {
			if (marketDataProvider == null) {
				final double defaultMarketPrice = settings.getDouble(DEFAULT_MARKET_PRICE_KEY);
				marketDataProvider = new MarketDataProvider() {
					public double getAsk(String symbol) {
						return defaultMarketPrice;
					}

					public double getBid(String symbol) {
						return defaultMarketPrice;
					}
				};
			} else {
				log.warn("Ignoring {} since provider is already defined.", DEFAULT_MARKET_PRICE_KEY);
			}
		}
	}

	private void initializeValidOrderTypes(SessionSettings settings) throws ConfigError, FieldConvertError {
		if (settings.isSetting(VALID_ORDER_TYPES_KEY)) {
			List<String> orderTypes = Arrays
					.asList(settings.getString(VALID_ORDER_TYPES_KEY).trim().split("\\s*,\\s*"));
			validOrderTypes.addAll(orderTypes);
		} else {
			validOrderTypes.add(OrdType.LIMIT + "");
		}
	}

	public void onCreate(SessionID sessionID) {
		Session.lookupSession(sessionID).getLog().onEvent("Valid order types: " + validOrderTypes);
	}

	public void onLogon(SessionID sessionID) {
		log.info("onLogon::" + sessionID);
	}

	public void onLogout(SessionID sessionID) {
		log.info("onLogout::" + sessionID);
	}

	public void toAdmin(quickfix.Message message, SessionID sessionID) {
		log.info("toAdmin::" + message.toString() + "::" + sessionID);
	}

	public void toApp(quickfix.Message message, SessionID sessionID) throws DoNotSend {
		log.info("toApp::" + message.toString() + "::" + sessionID);
	}

	public void fromAdmin(quickfix.Message message, SessionID sessionID) throws FieldNotFound, IncorrectDataFormat,
	IncorrectTagValue, RejectLogon {
		log.info("fromAdmin::" + message.toString() + "::" + sessionID);
		try {
			MsgType msgType = new MsgType();
			if (message.getHeader().getField(msgType).valueEquals(MsgType.LOGON)) {
				log.info("Received Logon message: " + message.toString());
			} else if (message.getHeader().getField(msgType).valueEquals(MsgType.HEARTBEAT)) {
				log.info("Received Heartbeat message: " + message.toString());
			} else if (message.getHeader().getField(new MsgType()).valueEquals(MsgType.SEQUENCE_RESET)) {
				int newSeqNo = message.getInt(new NewSeqNo().getField())+1;
				log.info("Received SEQUENCE_RESET message: " + message.toString() +" :: "+ newSeqNo);
				try {
					Session.lookupSession(sessionID).setNextSenderMsgSeqNum(newSeqNo);
				} catch (IOException e) {
					e.printStackTrace();
				}
				log.info("Sequence number reset to: " + newSeqNo);
			}
		} catch (FieldNotFound fieldNotFound) {
			fieldNotFound.printStackTrace();
		}
	}

	public void fromApp(quickfix.Message message, SessionID sessionID) throws FieldNotFound, IncorrectDataFormat,
	IncorrectTagValue, UnsupportedMessageType {
		log.info("fromApp::" + message.toString() + "::" + sessionID);
		MsgType msgType = new MsgType();
		if (message.getHeader().getField(msgType).valueEquals(MsgType.SECURITY_STATUS)) {
			sendMessage(sessionID, message);
		} else {
			crack(message, sessionID);
		}
	}


	private boolean isOrderExecutable(Message order, Price price) throws FieldNotFound {
		if (order.getChar(OrdType.FIELD) == OrdType.LIMIT) {
			BigDecimal limitPrice = new BigDecimal(order.getString(Price.FIELD));
			char side = order.getChar(Side.FIELD);
			BigDecimal thePrice = new BigDecimal("" + price.getValue());

			return (side == Side.BUY && thePrice.compareTo(limitPrice) <= 0)
					|| ((side == Side.SELL || side == Side.SELL_SHORT) && thePrice.compareTo(limitPrice) >= 0);
		}
		return true;
	}

	private Price getPrice(Message message) throws FieldNotFound {
		Price price;
		if (message.getChar(OrdType.FIELD) == OrdType.LIMIT && alwaysFillLimitOrders) {
			price = new Price(message.getDouble(Price.FIELD));
		} else {
			if (marketDataProvider == null) {
				throw new RuntimeException("No market data provider specified for market order");
			}
			char side = message.getChar(Side.FIELD);
			if (side == Side.BUY) {
				price = new Price(marketDataProvider.getAsk(message.getString(Symbol.FIELD)));
			} else if (side == Side.SELL || side == Side.SELL_SHORT) {
				price = new Price(marketDataProvider.getBid(message.getString(Symbol.FIELD)));
			} else {
				throw new RuntimeException("Invalid order side: " + side);
			}
		}
		return price;
	}

	private void sendMessage(SessionID sessionID, Message message) {
		try {
			Session session = Session.lookupSession(sessionID);
			if (session == null) {
				throw new SessionNotFound(sessionID.toString());
			}

			DataDictionaryProvider dataDictionaryProvider = session.getDataDictionaryProvider();
			if (dataDictionaryProvider != null) {
				try {
					dataDictionaryProvider.getApplicationDataDictionary(
							getApplVerID(session, message)).validate(message, true);
				} catch (Exception e) {
					LogUtil.logThrowable(sessionID, "Outgoing message failed validation: "
							+ e.getMessage(), e);
					return;
				}
			}

			session.send(message);
		} catch (SessionNotFound e) {
			log.error(e.getMessage(), e);
		}
	}

	private ApplVerID getApplVerID(Session session, Message message) {
		String beginString = session.getSessionID().getBeginString();
		if (FixVersions.BEGINSTRING_FIXT11.equals(beginString)) {
			return new ApplVerID(ApplVerID.FIX50);
		} else {
			return MessageUtils.toApplVerID(beginString);
		}
	}

	private void validateOrder(Message order) throws IncorrectTagValue, FieldNotFound {
		OrdType ordType = new OrdType(order.getChar(OrdType.FIELD));
		if (!validOrderTypes.contains(Character.toString(ordType.getValue()))) {
			log.error("Order type not in ValidOrderTypes setting");
			throw new IncorrectTagValue(ordType.getField());
		}
		if (ordType.getValue() == OrdType.MARKET && marketDataProvider == null) {
			log.error("DefaultMarketPrice setting not specified for market order");
			throw new IncorrectTagValue(ordType.getField());
		}
	}

	public void onMessage(quickfix.fix50.NewOrderSingle order, SessionID sessionID)
			throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
		try {
			validateOrder(order);

			OrderQty orderQty = order.getOrderQty();
			Price price = getPrice(order);

			quickfix.fix50.ExecutionReport accept = new quickfix.fix50.ExecutionReport(
					genOrderID(), genExecID(), new ExecType(ExecType.NEW), new OrdStatus(
							OrdStatus.NEW), order.getSide(), new LeavesQty(order.getOrderQty()
									.getValue()), new CumQty(0));

			accept.set(order.getClOrdID());
			accept.set(order.getSymbol());
			sendMessage(sessionID, accept);

			if (isOrderExecutable(order, price)) {
				quickfix.fix50.ExecutionReport executionReport = new quickfix.fix50.ExecutionReport(
						genOrderID(), genExecID(), new ExecType(ExecType.TRADE), new OrdStatus(
								OrdStatus.FILLED), order.getSide(), new LeavesQty(0), new CumQty(
										orderQty.getValue()));

				executionReport.set(order.getClOrdID());
				executionReport.set(order.getSymbol());
				executionReport.set(orderQty);
				executionReport.set(new LastQty(orderQty.getValue()));
				executionReport.set(new LastPx(price.getValue()));
				executionReport.set(new AvgPx(price.getValue()));

				sendMessage(sessionID, executionReport);
			}
		} catch (RuntimeException e) {
			LogUtil.logThrowable(sessionID, e.getMessage(), e);
		}
	}

	public void onMessage(quickfix. fix50.ExecutionReport executionReport, SessionID sessionID)
			throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
		try {
			quickfix.fix50.ExecutionAcknowledgement accept = new quickfix.fix50.ExecutionAcknowledgement(
					executionReport.getOrderID(), new ExecAckStatus(ExecAckStatus.ACCEPTED), executionReport.getExecID(), executionReport.getSide());

			sendMessage(sessionID, accept);
		} catch (RuntimeException e) {
			LogUtil.logThrowable(sessionID, e.getMessage(), e);
		}
	}
	
	public void onMessage(quickfix.fix50.TradeCaptureReport tradeReport, SessionID sessionID)
			throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
		try {
			quickfix.fix50.TradeCaptureReportAck tradeCaptureReportAck = new quickfix.fix50.TradeCaptureReportAck();
	        tradeCaptureReportAck.set(new TradeReportID(tradeReport.getTradeReportID().getValue()));
	        tradeCaptureReportAck.set(new TradeReportTransType(tradeReport.getTradeReportTransType().getValue()));
	        tradeCaptureReportAck.set(new ExecID(tradeReport.getExecID().getValue()));
	        tradeCaptureReportAck.set(new ExecType(tradeReport.getExecType().getValue()));
	        tradeCaptureReportAck.set(new TrdRptStatus(tradeReport.getTrdRptStatus().getValue()));
	        tradeCaptureReportAck.set(new LastQty(tradeReport.getLastQty().getValue()));
	        tradeCaptureReportAck.set(new Symbol(tradeReport.getSymbol().getValue()));
	        tradeCaptureReportAck.set(new SecurityID(tradeReport.getSecurityID().getValue()));
	        tradeCaptureReportAck.set(new SecurityStatus(tradeReport.getSecurityStatus().getValue()));
	        tradeCaptureReportAck.set(new TransactTime(LocalDateTime.now()));
	        sendMessage(sessionID, tradeCaptureReportAck);
		} catch (RuntimeException e) {
			LogUtil.logThrowable(sessionID, e.getMessage(), e);
		} 
	}
	public void onMessage(quickfix.fix50.SecurityDefinition security, SessionID sessionID)
			throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
		try {
			SecurityDefinitionUpdateReport securityDefinition = new SecurityDefinitionUpdateReport();
	        securityDefinition.set(security.getSecurityID());
	        securityDefinition.set(security.getSecurityResponseID()); 
	        securityDefinition.set(new SecurityResponseType(SecurityResponseType.ACCEPT_SECURITY_PROPOSAL_AS_IS));
	        securityDefinition.set(security.getSecurityDesc()); 
	        
			sendMessage(sessionID, securityDefinition);
		} catch (RuntimeException e) {
			LogUtil.logThrowable(sessionID, e.getMessage(), e);
		} 
	}
	public void onMessage(quickfix.fix50.DerivativeSecurityList derivativeSecurity, SessionID sessionID)
			throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {
		try {
			SecurityListUpdateReport securityList = new SecurityListUpdateReport();
			securityList.set(derivativeSecurity.getSecurityReqID());
			securityList.set(derivativeSecurity.getSecurityResponseID()); 
			securityList.set(derivativeSecurity.getSecurityRequestResult()); 
	        
			sendMessage(sessionID, securityList);
		} catch (RuntimeException e) {
			LogUtil.logThrowable(sessionID, e.getMessage(), e);
		} 
	}
	

	public OrderID genOrderID() {
		return new OrderID(Integer.toString(++m_orderID));
	}

	public ExecID genExecID() {
		return new ExecID(Integer.toString(++m_execID));
	}

	/**
	 * Allows a custom market data provider to be specified.
	 *
	 * @param marketDataProvider
	 */
	public void setMarketDataProvider(MarketDataProvider marketDataProvider) {
		this.marketDataProvider = marketDataProvider;
	}

	private int m_orderID = 0;
	private int m_execID = 0;
}