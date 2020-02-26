/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2019 Konrad Kollnig, University of Oxford
 */

package net.kollnig.missioncontrol.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.util.Log;

import androidx.collection.ArrayMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Database {
	/**
	 * Retrieves information for all apps
	 *
	 * @return A cursor pointing to the data. Caller must close the cursor.
	 * Cursor should have app name and leak summation based on a sort type
	 */
	public synchronized Map<String, Integer> getApps () {
		Map<String, Set<Company>> trackers = new ArrayMap<>();

		String[] columns = new String[]{COLUMN_APPID, COLUMN_HOSTNAME};
		Cursor cursor = getDatabase().query(true, TABLE_HISTORY, columns,
				null, null,
				null, null,
				null, null);

		if (cursor.moveToFirst()) {
			do {
				String appId = cursor.getString(cursor.getColumnIndex(COLUMN_APPID));
				Set<Company> companies = trackers.get(appId);
				if (companies == null) {
					companies = new HashSet<>();
					trackers.put(appId, companies);
				}

				// Add company
				String hostname = cursor.getString(cursor.getColumnIndex(COLUMN_HOSTNAME));
				Company company = getCompany(hostname);
				if (company != null)
					companies.add(company);
			} while (cursor.moveToNext());
		}
		cursor.close();

		// Reduce to counts
		Map<String, Integer> trackerCounts = new ArrayMap<>();
		for (Map.Entry<String, Set<Company>> entry : trackers.entrySet()) {
			trackerCounts.put(entry.getKey(), entry.getValue().size());
		}

		return trackerCounts;
	}

	/**
	 * Retrieve info for CSV export
	 * @param appId The id of the app to be dumped
	 * @return All found trackers
	 */
	public Cursor getAppInfo (String appId) {
		return getDatabase().rawQuery(
				"SELECT * FROM " + TABLE_HISTORY + " WHERE " + COLUMN_APPID + " = ?", new String[]{appId});
	}

	/**
	 * Retrieves information about all seen trackers
	 *
	 * @return A list of seen trackers
	 */
	public synchronized List<Tracker> getTrackers (String mAppId) {
		Map<String, Tracker> ownerToCompany = new ArrayMap<>();

		String[] columns = new String[]{COLUMN_HOSTNAME};

		Cursor cursor = getDatabase().query(true, TABLE_HISTORY,
				columns,
				COLUMN_APPID + " = ?",
				new String[]{mAppId},
				null, // groupBy
				null,
				null, null);

		if (cursor.moveToFirst()) {
			outer: do {
				String hostname = cursor.getString(cursor.getColumnIndex(COLUMN_HOSTNAME));
				Company company = getCompany(hostname);
				if (company == null)
					continue;

				String owner = company.owner;
				String name = company.name;
				if (owner == null || owner.equals("null")) owner = name;

				Tracker ownerCompany = ownerToCompany.get(owner);
				if (ownerCompany == null) {
					ownerCompany = new Tracker();
					ownerCompany.name = owner;
					ownerCompany.children = new ArrayList<>();
					ownerToCompany.put(owner, ownerCompany);
				}

				// avoid children duplicates
				for (Tracker child: ownerCompany.children) {
					if (child.name.equals(name))
						continue outer;
				}

				Tracker child = new Tracker();
				child.name = name;
				child.owner = owner;
				ownerCompany.children.add(child);
			} while (cursor.moveToNext());
		}

		cursor.close();

		// map to list
		List<Tracker> trackerList = new ArrayList<>(ownerToCompany.values());

		// sort lists
		Collections.sort(trackerList, (o1, o2) -> o1.name.compareTo(o2.name));
		for (Tracker child: trackerList) {
			Collections.sort(child.children, (o1, o2) -> o1.name.compareTo(o2.name));
		}

		return trackerList;
	}

	/* ****** COLUMN NAMES PERTAINING TO {@link #TABLE_HISTORY} ****** */
	private static final String COLUMN_APPID = "appname";
	private static final String COLUMN_REMOTE_IP = "remoteIp";
	private static final String COLUMN_HOSTNAME = "hostname";
	private static final String COLUMN_COMPANYNAME = "companyName";
	private static final String COLUMN_COMPANYOWNER = "companyOwner";
	private static final String COLUMN_COUNT = "count";
	private static final String TAG = Database.class.getSimpleName();
	private static final String DATABASE_NAME = "trackers.db";
	/**
	 * Keeps history of leaks
	 */
	private static final String TABLE_HISTORY = "TABLE_HISTORY";
	/* ****** COLUMN NAMES PERTAINING TO ALL TABLES ****** */
	private static final String COLUMN_ID = "_id";
	/**
	 * Used in {@link #TABLE_HISTORY} to indicate when the leak occured
	 */
	private static final String COLUMN_TIME = "timestampt";
	private static final int DATABASE_VERSION = 3;
	private static Map<String, Company> hostnameToCompany = new ArrayMap<>();
	static Set<String> necessaryCompanies = new HashSet<>();
	private static Database instance;
	private final SQLHandler sqlHandler;
	private SQLiteDatabase _database;

	/**
	 * Database constructor
	 */
	private Database (Context c) {
		sqlHandler = new SQLHandler(c);
		loadTrackerDomains(c);
	}

	/**
	 * Singleton getter.
	 *
	 * @param c context used to open the database
	 * @return The current instance of PrivacyDB, if none, a new instance is created.
	 * After calling this method, the database is open for writing.
	 */
	public static Database getInstance (Context c) {
		if (instance == null)
			instance = new Database(c);

		if (instance._database == null) {
			instance._database = instance.sqlHandler.getWritableDatabase();
		}

		return instance;
	}

	public Company getCompany (String hostname) {
		Company company = null;

		if (hostnameToCompany.containsKey(hostname)) {
			company = hostnameToCompany.get(hostname);
		} else { // check subdomains
			for (int i = 0; i < hostname.length(); i++){
				if (hostname.charAt(i) == '.') {
					company = hostnameToCompany.get(hostname.substring(i+1));
					if (company != null)
						break;
				}
			}
		}

		return company;
	}

	private SQLiteDatabase getDatabase () {
		if (this.isClose()) {
			_database = sqlHandler.getWritableDatabase();
		}
		return _database;
	}

	public long count () {
		long count = DatabaseUtils.queryNumEntries(getDatabase(), TABLE_HISTORY);

		return count;
	}

	/**
	 * Close the database
	 */
	public synchronized void close () {
		sqlHandler.close();
		_database = null;
	}

	private synchronized boolean isClose () {
		return _database == null;
	}

	/**
	 * Logs the leak for historical purposes
	 *
	 * @param appName  the name of the app responsible for the leak
	 * @param remoteIp the IP address contacted
	 * @param hostname the resolved hostname from remoteIp
	 * @return the row ID of the updated row, or -1 if an error occurred
	 */
	private synchronized long logPacket (String appName, String remoteIp, String hostname, String companyName, String companyOwner) {
		SQLiteDatabase db = getDatabase();

		long count = DatabaseUtils.queryNumEntries(db, TABLE_HISTORY,
				COLUMN_HOSTNAME + " = ?", new String[] {hostname});
		if (count > 0)
			return 0;

		// Add leak to history
		ContentValues cv = new ContentValues();
		cv.put(COLUMN_APPID, appName);
		cv.put(COLUMN_HOSTNAME, hostname);
		cv.put(COLUMN_COMPANYNAME, companyName);
		cv.put(COLUMN_COMPANYOWNER, companyOwner);
		cv.put(COLUMN_TIME, System.currentTimeMillis());
		cv.put(COLUMN_REMOTE_IP, remoteIp);

		return db.insert(TABLE_HISTORY, null, cv);
	}

	public void logPacketAsyncTask (Context context,
	                                String packageName, String remoteIp, String hostname) {
		LogPacketTask task = new LogPacketTask(context, packageName, remoteIp, hostname);
		task.execute();
	}


	public void loadTrackerDomains (Context context) {
		try {
			// Read domain list
			InputStream is = context.getAssets().open("companyDomains.json");
			int size = is.available();
			byte[] buffer = new byte[size];
			is.read(buffer);
			is.close();
			String json = new String(buffer, StandardCharsets.UTF_8);

			JSONArray jsonCompanies = new JSONArray(json);
			for (int i = 0; i < jsonCompanies.length(); i++) {
				JSONObject jsonCompany = jsonCompanies.getJSONObject(i);

				Company company;
				String country = jsonCompany.getString("country");
				String name = jsonCompany.getString("owner_name");
				String parent = null;
				if (!jsonCompany.isNull("root_parent")) {
					parent = jsonCompany.getString("root_parent");
				}
				Boolean necessary;
				if (jsonCompany.has("necessary")) {
					necessary = jsonCompany.getBoolean("necessary");
					necessaryCompanies.add(name);
				} else {
					necessary = false;
				}
				company = new Company(country, name, parent, necessary);

				JSONArray domains = jsonCompany.getJSONArray("doms");
				for (int j = 0; j < domains.length(); j++) {
					hostnameToCompany.put(domains.getString(j), company);
				}
			}
		} catch (IOException | JSONException e) {
			Log.d(TAG, "Loading companies failed.. ", e);
		}
	}

	private static class SQLHandler extends SQLiteOpenHelper {

		SQLHandler (Context context) {
			super(context, DATABASE_NAME, null, DATABASE_VERSION);
		}

		/**
		 * Called when database is first created
		 */
		@Override
		public void onCreate (SQLiteDatabase db) {
			db.execSQL("CREATE TABLE " + TABLE_HISTORY + "("
					+ COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ COLUMN_APPID + " TEXT NOT NULL, "
					+ COLUMN_REMOTE_IP + " TEXT NOT NULL, "
					+ COLUMN_HOSTNAME + " TEXT NOT NULL, "
					+ COLUMN_COMPANYNAME + " TEXT, "
					+ COLUMN_COMPANYOWNER + " TEXT, "
					+ COLUMN_TIME + " INTEGER DEFAULT 0);");
			db.execSQL("CREATE INDEX idx_history_hostname ON " + TABLE_HISTORY + "(" + COLUMN_HOSTNAME + ")");
		}

		/**
		 * If database exists, this method will be called
		 */
		@Override
		public void onUpgrade (SQLiteDatabase db, int oldVersion, int newVersion) {
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_HISTORY);
			onCreate(db);
		}

	}

	static class LogPacketTask extends AsyncTask<Void, Void, Boolean> {
		private final Context mContext;
		private final String packageName;
		private final String remoteIp;
		private final String hostname;

		LogPacketTask (Context context, String packageName, String remoteIp, String hostname) {
			this.mContext = context;
			this.packageName = packageName;
			this.remoteIp = remoteIp;
			this.hostname = hostname;
		}

		@Override
		protected Boolean doInBackground (Void... voids) {
			Database db = Database.getInstance(mContext);

			Company company = db.getCompany(hostname);
			if (company == null) {
				db.logPacket(this.packageName, this.remoteIp, this.hostname, null, null);
			} else {
				db.logPacket(this.packageName, this.remoteIp, this.hostname, company.name, company.owner);
			}

			return true;
		}
	}
}
