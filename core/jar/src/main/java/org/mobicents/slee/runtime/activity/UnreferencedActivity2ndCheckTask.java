package org.mobicents.slee.runtime.activity;

import org.apache.log4j.Logger;
import org.mobicents.slee.container.SleeContainer;
import org.mobicents.slee.runtime.activity.ActivityContextState;
import org.mobicents.slee.runtime.transaction.SleeTransactionManager;

public class UnreferencedActivity2ndCheckTask implements Runnable {

	private static final Logger logger = Logger
			.getLogger(UnreferencedActivity2ndCheckTask.class);
	private static final SleeContainer sleeContainer = SleeContainer
			.lookupFromJndi();

	private final String acId;

	public UnreferencedActivity2ndCheckTask(String acId) {
		super();
		this.acId = acId;
	}

	public void run() {

		SleeTransactionManager txManager = sleeContainer
				.getTransactionManager();
		boolean rollback = true;
		try {
			txManager.begin();
			ActivityContext ac = sleeContainer.getActivityContextFactory()
					.getActivityContext(acId, false);
			if (ac != null && ac.getState() == ActivityContextState.ACTIVE) {
				ac.unreferencedActivity2ndCheck();
			}
			rollback = false;
		} catch (Exception e) {
			logger.error(
					"failure while running unrefered activity 2nd check for ac with id "
							+ acId, e);
		} finally {
			try {
				if (rollback) {
					txManager.rollback();
				} else {
					txManager.commit();
				}
			} catch (Exception e) {
				logger
						.error(
								"failure while ending tx, while running unrefered activity 2nd check for ac with id "
										+ acId, e);
			}
		}
	}
}
