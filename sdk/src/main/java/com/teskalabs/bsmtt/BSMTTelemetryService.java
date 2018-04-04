package com.teskalabs.bsmtt;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Messenger;
import android.support.annotation.RequiresPermission;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;

import com.teskalabs.bsmtt.cell.CellData;
import com.teskalabs.bsmtt.connector.Connector;
import com.teskalabs.bsmtt.events.BasicEvent;
import com.teskalabs.bsmtt.events.CellEvent;
import com.teskalabs.bsmtt.events.ConnectionEvent;
import com.teskalabs.bsmtt.events.JsonEvent;
import com.teskalabs.bsmtt.events.PhoneEvent;
import com.teskalabs.bsmtt.location.LocationHelper;
import com.teskalabs.bsmtt.messaging.BSMTTClientHandler;
import com.teskalabs.bsmtt.messaging.BSMTTListener;
import com.teskalabs.bsmtt.messaging.BSMTTServerHandler;
import com.teskalabs.bsmtt.messaging.BSMTTServiceConnection;
import com.teskalabs.bsmtt.phonestate.PhoneListener;
import com.teskalabs.bsmtt.phonestate.PhoneListenerCallback;
import com.teskalabs.bsmtt.phonestate.PhoneResponse;
import com.teskalabs.seacat.android.client.SeaCatClient;

/**
 * This class gets information about the phone and its behavior and sends them to the server when necessary.
 * @author Stepan Hruska, Premysl Cerny
 */
public class BSMTTelemetryService extends Service implements PhoneListenerCallback, LocationListener {
	public static final String LOG_TAG = "BSMTTelemetryService";

	// Event constants
	public static final int BASIC_EVENT_INDEX = 0;
	public static final int CONNECTION_EVENT_INDEX = 1;
	public static final int PHONE_EVENT_INDEX = 2;
	public static final int CELL_EVENT_INDEX = 3;

	// Telephony manager
	private TelephonyManager TMgr;
	// Sending data
	private Connector mConnector;
	private BroadcastReceiver mSeaCatReceiver;
	// Listeners
	private PhoneListener PhoneStateListener;
	// Connection with activities
	private Messenger mMessenger;
	private BSMTTServerHandler mMessengerServer;
	// List of JSON events
	ArrayList<JsonEvent> mEvents;
	// The current location
	private Location mLocation;

	/**
	 * A basic constructor.
	 */
	public BSMTTelemetryService() {
		// Messaging
		mMessengerServer = new BSMTTServerHandler(this);
		mMessenger = new Messenger(mMessengerServer);
		// Events
		mEvents = new ArrayList<>();
	}

	/**
	 * Makes sure that all listeners and necessary objects are removed after shutting down the service.
	 */
	@Override
	public void onDestroy() {
		// Location listener
		LocationManager locationManager = (LocationManager)this.getSystemService(Context.LOCATION_SERVICE);
		if (locationManager != null) {
			locationManager.removeUpdates(this);
		}
		// Phone listener
		TMgr.listen(PhoneStateListener, android.telephony.PhoneStateListener.LISTEN_NONE);
		// Connector
		mConnector.delete();
		if (mSeaCatReceiver != null) {
			unregisterReceiver(mSeaCatReceiver);
			mSeaCatReceiver = null;
		}
		// This
		super.onDestroy();
	}

	/**
	 * Runs the service which obtains phone-related data and sends them to a server.
	 * @param context Context
	 */
	@RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
			Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_NETWORK_STATE})
	public static void run(Context context) {
		// Starting the service
		Intent intent = new Intent(context, BSMTTelemetryService.class);
		context.startService(intent);
	}

	/**
	 * Creates a connection between a service and an activity to communicate through messages.
	 * @param context Context
	 * @param listener BSMTTListener
	 * @return BSMTTServiceConnection
	 */
	public static BSMTTServiceConnection startConnection(Context context, BSMTTListener listener) {
		// Binding the service
		Intent intent = new Intent(context, BSMTTelemetryService.class);
		try {
			Messenger receiveMessenger = new Messenger(new BSMTTClientHandler(context, listener));
			BSMTTServiceConnection connection = new BSMTTServiceConnection(receiveMessenger);
			context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
			return connection;
		} catch (SecurityException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Shuts down a connection between an activity and a service.
	 * @param context Context
	 * @param connection BSMTTServiceConnection
	 */
	public static void stopConnection(Context context, BSMTTServiceConnection connection) {
		// Unbinding
		if (connection != null)
			context.unbindService(connection);
	}

	/**
	 * Stops the service.
	 * @param context Context
	 */
	public static void stop(Context context) {
		Intent intent = new Intent(context, BSMTTelemetryService.class);
		// Stopping
		context.stopService(intent);
	}

	/**
	 * Checks if the service is already running.
	 * @param context Context
	 * @return boolean
	 */
	public static boolean isRunning(Context context) {
		ActivityManager manager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
		try {
			for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
				if ("com.teskalabs.bsmtt.BSMTTelemetryService".equals(service.service.getClassName())) {
					return true;
				}
			}
		} catch (NullPointerException e) {
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * Checks and returns the events.
	 * @return ArrayList<JsonEvent>
	 */
	public ArrayList<JsonEvent> getEvents() {
		// Checking that the events are ready to be read
		if (mLocation == null)
			return new ArrayList<>();
		// Returning the events
		return mEvents;
	}

	/**
	 * Checks permissions, initializes the service and obtains the data.
	 * @param intent Intent
	 * @param flags int
	 * @param startId int
	 * @return int
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			if (!BSMTTelemetryHelper.isFineLocationPermissionGranted(this)
					|| !BSMTTelemetryHelper.isPhoneStatePermissionGranted(this)) {
				Log.e(LOG_TAG, getResources().getString(R.string.log_permissions));
				stopSelf();
			} else {
				SeaCatClient.initialize(this);
				// Initializing the sending object
				mConnector = new Connector(this, getResources().getString(R.string.connector_url));
				// Registering the SeaCat receiver
				IntentFilter intentFilter = new IntentFilter();
				intentFilter.addAction(SeaCatClient.ACTION_SEACAT_STATE_CHANGED);
				intentFilter.addAction(SeaCatClient.ACTION_SEACAT_CSR_NEEDED);
				intentFilter.addAction(SeaCatClient.ACTION_SEACAT_CLIENTID_CHANGED);
				intentFilter.addCategory(SeaCatClient.CATEGORY_SEACAT);
				if (isSeaCatReady(SeaCatClient.getState())) {
					mConnector.setReady(); // we are ready to send data!
				}
				mSeaCatReceiver = new BroadcastReceiver() {
					@Override
					public void onReceive(Context context, Intent intent) {
						if (intent.hasCategory(SeaCatClient.CATEGORY_SEACAT)) {
							String action = intent.getAction();
							if (action.equals(SeaCatClient.ACTION_SEACAT_STATE_CHANGED)) {
								String state = intent.getStringExtra(SeaCatClient.EXTRA_STATE);
								if (isSeaCatReady(state)) {
									mConnector.setReady(); // we are ready to send data!
								} else {
									mConnector.unsetReady();
								}
							}
						}
					}
				};
				registerReceiver(mSeaCatReceiver, intentFilter);
				initialize(); // initialize
			}
		}
		return Service.START_NOT_STICKY;
	}

	/**
	 * Checks if the SeaCat is ready.
	 * @param state String
	 * @return boolean
	 */
	private boolean isSeaCatReady(String state) {
		return ((state.charAt(3) == 'Y') && (state.charAt(4) == 'N') && (state.charAt(0) != 'f'));
	}

	/**
	 * Takes care of binding the activity and service together.
	 * @param intent Intent
	 * @return IBinder
	 */
	@Override
	public IBinder onBind(Intent intent) {
		return mMessenger.getBinder();
	}

	/**
	 * Reacts to phone state change events.
	 * @param phoneResponse PhoneResponse
	 */
	@Override
	public void onPhoneResponseChange(PhoneResponse phoneResponse) {
		// Related to the phone response
		if (phoneResponse != null) {
			long txBytes = TrafficStats.getMobileTxBytes();
			long rxBytes = TrafficStats.getMobileRxBytes();
			if (txBytes > 0) phoneResponse.setTX(txBytes);
			if (rxBytes > 0) phoneResponse.setRX(rxBytes);
			// Saving the phone response
			PhoneEvent phoneEvent = (PhoneEvent)mEvents.get(PHONE_EVENT_INDEX);
			phoneEvent.changePhoneResponse(phoneResponse);
		}
		// Refreshing variables
		refreshAllInfo();
		// Sending data if necessary
		sendDataIfNeeded();
	}

	/**
	 * Initializes the service's objects and loads data about the phone.
	 */
	public void initialize() {
		// Initializing necessary variables
		// dataNetStr = "";
		// Adding events to the list
		mEvents.add(BASIC_EVENT_INDEX, new BasicEvent(this));
		mEvents.add(CONNECTION_EVENT_INDEX, new ConnectionEvent());
		mEvents.add(PHONE_EVENT_INDEX, new PhoneEvent());
		mEvents.add(CELL_EVENT_INDEX, new CellEvent());
		// Getting the objects where we are getting the information from
		TMgr = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
		PhoneStateListener = new PhoneListener(this, TMgr);
		// Initializing the location listener
		mLocation = null;
		LocationManager locationManager = (LocationManager)this.getSystemService(Context.LOCATION_SERVICE);
		if (locationManager != null) {
			try {
				locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, this);
				mLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
				if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
					locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
					Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
					if (location != null)
						mLocation = location;
				}
				JsonEvent.changeLocationAtAll(mEvents, mLocation); // saving
			} catch (SecurityException e) {
				e.printStackTrace();
				Log.e(LOG_TAG, getResources().getString(R.string.location_permissions));
			}
		}
		// Initializing the phone listener
		TMgr.listen(PhoneStateListener,PhoneListener.LISTEN_SIGNAL_STRENGTHS | PhoneListener.LISTEN_CELL_LOCATION |
				PhoneListener.LISTEN_DATA_CONNECTION_STATE| PhoneListener.LISTEN_DATA_ACTIVITY|
				PhoneListener.LISTEN_CALL_STATE|PhoneListener.LISTEN_CELL_INFO|PhoneListener.LISTEN_SERVICE_STATE);
		// Refreshing variables
		refreshAllInfo();
	}

	/**
	 * Refreshes all information that might have changed.
	 */
	private void refreshAllInfo() {
		// Getting the basic phone information
		retrieveBasicPhoneInformation();
		// Getting the advanced phone information
		refreshAdvancedPhoneInformation();
	}

	/**
	 * Gets the basic information about the phone (dimensions).
	 */
	private void retrieveBasicPhoneInformation() {
		// Phone information
		try {
			JsonEvent.changePhoneInfoAtAll(mEvents,
					BSMTTelemetryHelper.getPhoneVendorModel(),
					BSMTTelemetryHelper.getPhoneTypeStr(TMgr),
					TMgr.getSubscriberId(),
					TMgr.getDeviceId(),
					TMgr.getLine1Number(),
					TMgr.getSimSerialNumber());
		} catch (SecurityException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Refreshes advanced information about the phone and its current state.
	 */
	private void refreshAdvancedPhoneInformation() {
		// Network
		String MCC_MNC = TMgr.getNetworkOperator();
		String net_name = TMgr.getNetworkOperatorName();
		JsonEvent.changePhoneNetworkAtAll(mEvents, MCC_MNC, net_name);

		// Connection
		// Roaming
		int roaming = 0;
		if (TMgr.isNetworkRoaming()) {
			roaming = 1;
		}
		if (TMgr.getNetworkType() == TelephonyManager.NETWORK_TYPE_UNKNOWN){
			roaming = -1;
		}
		// Other info
		boolean haveMobileConnection = BSMTTelemetryHelper.haveMobileConnection(this);
		int dconn = TMgr.getDataState();
		// if (m_phoneResponse != null) {
		// dataNetStr = BSMTTelemetryHelper.getNetworkType(m_phoneResponse.getData_networkType());
		// }
		// Saving
		ConnectionEvent connectionEvent = (ConnectionEvent)mEvents.get(CONNECTION_EVENT_INDEX);
		connectionEvent.changeNetwork(haveMobileConnection, dconn, roaming);

		// Cell info
		CellEvent cellEvent = (CellEvent)mEvents.get(CELL_EVENT_INDEX);
		CellData cellData = cellEvent.getCellData();
		cellData = BSMTTelemetryHelper.getCellLocation(cellData, TMgr, cellEvent.getPhoneTypeStr());
		cellData = BSMTTelemetryHelper.getCellSignal(cellData, TMgr);
		cellEvent.changeCell(cellData);
	}

	/**
	 * Receives a new location update.
	 * @param location Location
	 */
	public void onLocationChanged(Location location) {
		// Checks if the current location is better than the last one
		if (mLocation == null) {
			mLocation = location;
		} else {
			if (LocationHelper.isBetterLocation(location, mLocation)) {
				mLocation = location;
			} else {
				return;
			}
		}
		// Saving the location
		JsonEvent.changeLocationAtAll(mEvents, mLocation);
		// Refreshing variables
		refreshAllInfo();
		// Sending data if necessary
		sendDataIfNeeded();
	}

	/**
	 * Reacts to the location's onStatusChanged event.
	 * @param provider String
	 * @param status int
	 * @param extras Bundle
	 */
	public void onStatusChanged(String provider, int status, Bundle extras) {}

	/**
	 * Reacts to the onProviderEnabled event.
	 * @param provider String
	 */
	public void onProviderEnabled(String provider) {}

	/**
	 * Reacts to the onProviderDisabled event.
	 * @param provider String
	 */
	public void onProviderDisabled(String provider) {}


	/**
	 * Checks if it is necessary to send the data, and if so, it performs the sending.
	 */
	public void sendDataIfNeeded() {
		// Checking before sending
		ArrayList<JsonEvent> events = getEvents();
		// Sending the data
		for (int i = 0; i < events.size(); i++) {
			JsonEvent event = events.get(i);
			if (event.isReady()) {
				sendJSON(event.receiveEvent());
			}
		}
	}

	/**
	 * Sends JSON to all connectors.
	 * @param JSON JSONObject
	 */
	private void sendJSON(JSONObject JSON) {
		if (JSON != null) {
			try {
				JSONObject sendingJSON = new JSONObject(JSON.toString());
				// Adding the data to the sender
				mConnector.send(sendingJSON);
				// Passing the data to activities
				mMessengerServer.sendJSON(sendingJSON);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
	}
}
