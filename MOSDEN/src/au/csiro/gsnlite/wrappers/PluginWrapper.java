package au.csiro.gsnlite.wrappers;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;

import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import au.csiro.gsnlite.IFunction;
import au.csiro.gsnlite.beans.AddressBean;
import au.csiro.gsnlite.beans.DataField;
import au.csiro.gsnlite.beans.DataField4Plugins;
import au.csiro.gsnlite.beans.StreamElement4Plugins;
import au.csiro.gsnlite.utils.ApplicationObject;
import au.csiro.gsnlite.utils.Logger;

/**
 * This wrapper presents a MultiFormat protocol in which the data comes from the
 * system clock. Think about a sensor network which produces packets with
 * several different formats. In this example we have 3 different packets
 * produced by three different types of sensors. Here are the packet structures
 * : [temperature:double] , [light:double] , [temperature:double, light:double]
 * The first packet is for sensors which can only measure temperature while the
 * latter is for the sensors equipped with both temperature and light sensors.
 * 
 */
public class PluginWrapper extends AbstractWrapper {

	private static transient Logger logger = Logger.getInstance();
	private static String TAG = PluginWrapper.class.getSimpleName();
	private static String ACTION = "au.csiro.sensmalite.mainapp.intent.action.PICK_PLUGIN";

	private DataField[] collection;
	private int counter;
	private AddressBean params;
	private long rate = 1000;

	private IFunction opService;
	private String category;
	private DataField4Plugins[] collection4Plugins;
	private StreamElement4Plugins[] streamElement4Plugins;
	private Serializable[] dataRow;
	private ServiceConnection opServiceConnection;
	private boolean isBinded;

	public boolean initialize() {
		logger.debug("charith", "problem22");
		setName("MultiFormatWrapper" + counter++);
		params = getActiveAddressBean();
		logger.debug("charith", "problem23");
		if (params.getPredicateValue("rate") != null) {
			rate = (long) Integer.parseInt(params.getPredicateValue("rate"));

			logger.info(TAG,
					"Sampling rate set to " + params.getPredicateValue("rate")
							+ " msec.");
		}
		category = params.getPredicateValue("plugin");
		logger.debug("charith", "problem24");
		isBinded = bindOpService();
		
//		try {
//			Thread.sleep(5000);
//		} catch (InterruptedException e1) {
//			// TODO Auto-generated catch block
//			e1.printStackTrace();
//		}
//		
		logger.debug("charith", Boolean.toString(isBinded));
		while (!isBinded) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		try {
			logger.debug("charith", "problem25");
			collection4Plugins = opService.getDataStructure();
			logger.debug("charith", "read structure");
			logger.debug("charith", "problem26");
			collection = new DataField[collection4Plugins.length];

			for (int i = 0; i < collection4Plugins.length; i++) {
				collection[i] = new DataField(collection4Plugins[i].getName(),
						collection4Plugins[i].getType(),
						collection4Plugins[i].getDescription());
			}

		} catch (RemoteException e) {
			e.printStackTrace();
		}
		return true;
	}

	public void run() {

		while (isActive()) {
			try {
				Thread.sleep(rate);
			} catch (InterruptedException e) {
				logger.error(TAG, e.getMessage());
			}

			try {
				streamElement4Plugins = opService.getReadings();
				logger.debug("charith", "read values");
				Serializable[] dataRow = new Serializable[streamElement4Plugins.length];
				for (int i = 0; i < dataRow.length; i++) {
					dataRow[i] = streamElement4Plugins[i].getValue();
				}
				postStreamElement(dataRow);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public DataField[] getOutputFormat() {
		return collection;
	}

	public String getWrapperName() {
		return "MultiFormat Sample Wrapper";
	}

	public void dispose() {
		counter--;
		releaseOpService();
	}

	private boolean bindOpService() {
		logger.debug("charith", "problem27");
		if (category != null) {
			logger.debug("charith", "problem28");

			Intent i = new Intent(ACTION);
			i.addCategory(category);
			opServiceConnection = new ServiceConnection() {

				@Override
				public void onServiceDisconnected(ComponentName name) {
					logger.debug("charith", "problem31");
					opService = null;
				}

				@Override
				public void onServiceConnected(ComponentName name,
						IBinder service) {
					logger.debug("charith", "problem29");
					opService = IFunction.Stub.asInterface(service);
					logger.debug("charith", "H: "+Boolean.toString(opServiceConnection==null));
					logger.debug("charith", "problem30");
					// opService.registerCallback(mCallback);
					isBinded = true;
				}
			};
			logger.debug("charith", "problem32");

			ApplicationObject.getGsnLiteMainActivity().bindService(
					i, opServiceConnection, Context.BIND_AUTO_CREATE);
			// logger.debug("charith",Boolean.toString(b));
			logger.debug("charith", "problem40");
			 logger.debug("charith",Boolean.toString(isBinded));
			while (!isBinded) {
				logger.debug("charith", "problem41");
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		return isBinded;
	}

	private void releaseOpService() {
		ApplicationObject.getGsnLiteMainActivity().unbindService(
				opServiceConnection);
		opServiceConnection = null;
	}

	// class OpServiceConnection implements ServiceConnection {
	// public void onServiceConnected(ComponentName className,
	// IBinder boundService) {
	// opService = IFunction.Stub.asInterface((IBinder)boundService);
	// Log.d(TAG, "onServiceConnected");
	// //opService.registerCallback(mCallback);
	//
	// }
	//
	// public void onServiceDisconnected(ComponentName className) {
	// opService = null;
	// Log.d(TAG, "onServiceDisconnected");
	// }
	//
	// @Override
	// public void onServiceConnected(ComponentName name, IBinder service) {
	// opService = IFunction.Stub.asInterface((IBinder)service);
	//
	// }
	// };
}
