package com.teskalabs.blackswan.gargoyle.messaging;

import android.os.Message;

/**
 * An interface through which to receive messages from the SDK service.
 * @author Premysl Cerny
 */
public interface BSGargoyleListener {
	/**
	 * Receives a message where an action can be taken.
	 * @param msg Message
	 * @return boolean The message was processed.
	 */
	boolean onReceiveMessage(Message msg);
}
