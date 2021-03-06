package utb.dip.jp.netalis.util;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.widget.Toast;

import utb.dip.jp.netalis.model.TaskStatus;
import utb.dip.jp.netalis.model.Task;

public class DBAdapter {

    static final String DATABASE_NAME = "netalis.db";
    static final int DATABASE_VERSION = 7;

    public static final int LIMIT_COUNT = 200;
    protected final Context context;
    protected DatabaseHelper dbHelper;
    protected SQLiteDatabase db;

    public DBAdapter(Context context){
        this.context = context;
        dbHelper = new DatabaseHelper(this.context);
    }

    //
    // SQLiteOpenHelper
    //

    private static class DatabaseHelper extends SQLiteOpenHelper {

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE tasks (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "uuid TEXT NOT NULL," +
                    "task TEXT NOT NULL," +
                    "status INTEGER NOT NULL," +
                    "color TEXT," +
                    "priority INTEGER NOT NULL DEFAULT 0," +
                    "lastupdate TEXT NOT NULL);"
            );
            db.execSQL("CREATE INDEX idx_tasks ON tasks(uuid);");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN uuid;");
                db.execSQL("CREATE INDEX idx_tasks ON tasks(uuid);");
                Cursor cursor = db.rawQuery("SELECT _id from tasks", null);
                while(cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    db.execSQL(
                        "UPDATE tasks SET uuid = ? WHERE _id = ?",
                        strings(UUID.randomUUID().toString(), id)
                    );
                }
            }
            if (oldVersion < 7) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;");
            }
        }

    }

    //
    // Adapter Methods
    //

    public DBAdapter open() {
        db = dbHelper.getWritableDatabase();
        return this;
    }


    public void close(){
        dbHelper.close();
    }

    //
    // App Methods
    //

    /**
     * タスク削除
     * @param task  タスク
     * @return 削除件数
     */
    public int deleteTask(Task task) {
        return db.delete("tasks", "uuid=?", strings(task.uuid));
    }

    /** キャンセルにある30日以上のタスクを削除した日。 */
    public String lastDeleteCanceledTaskDate = "";
    /**
     * キャンセルにある30日以上のタスクを削除。
     */
    public int deleteCanceledTask() {
        // なんかいっぱい呼ばれるので1日1回に制限しておく…
        String today = MyDate.now().format("yyyyMMdd");
        if (today.equals(lastDeleteCanceledTaskDate)) {
            return 0;
        }
        lastDeleteCanceledTaskDate = today;
        // 削除処理
        String expireDate = MyDate.now().addDays(- Config.EXPIRE_DAYS).format();
        return db.delete(
            "tasks",
            "status = ? " +
            "and lastupdate < ? ",
            strings(
                TaskStatus.CANCEL.dbValue,
                expireDate
            )
        );
    }

    String[] TASKS_COLUMNS = new String[]{"uuid", "task", "status", "color", "priority", "lastupdate"};

    /**
     * 全タスクリスト取得
     * @param offset limit句のオフセット
     * @param count limit句の件数
     * @return Taskのリスト
     */
    public List<Task> selectAllTasks(long offset, long count) {
        if (count < 1) {
            return new ArrayList<Task>();
        }
        Cursor cursor = db.query(
                "tasks",
                TASKS_COLUMNS,
                null, // where
                null, // binding values
                null, // group
                null, // having
                "status, lastupdate desc", // oder by
                offset + "," + count // limit
        );
        return selectTasks(cursor, true);
    }

    /**
     * 全タスクリストの件数
     * @return 全タスクリストの件数
     */
    public long selectCountAllTasks() {
        Cursor cursor = db.rawQuery("select count(*) from tasks", null);
        cursor.moveToLast();
        try {
            return cursor.getLong(0);
        } finally {
            cursor.close();
        }
    }

    /**
     * タスクリストをoffsetから#LIMIT_COUNT件取得する。
     * @param status ステータス
     * @param offset オフセット
     * @return Taskのリスト
     */
    public List<Task> selectLimitedTasks(TaskStatus status, int offset) {
        Cursor cursor = db.query(
                "tasks",
                TASKS_COLUMNS,
                "status=?",
                strings(status.dbValue),
                null, // group
                null, // having
                // order by
                (status == TaskStatus.TODO ? "priority desc, " : "") +
                "lastupdate desc",
                offset +", " + LIMIT_COUNT
        );
        return selectTasks(cursor, true);
    }

    /**
     * タスクリスト取得
     * @param uuid UUID
     * @return Taskのリスト
     */
    public Task selectTask(String uuid) {
        Cursor cursor = db.query(
                "tasks",
                TASKS_COLUMNS,
                "uuid=?",
                strings(uuid),
                null, // group
                null, // having
                null // oder by
        );
        List<Task> list = selectTasks(cursor, true);
        return list.size() == 0 ? null : list.get(0);
    }

    /**
     * タスクリスト取得
     * @param cursor DBカーソル
     * @return Taskのリスト
     */
    public List<Task> selectTasks(Cursor cursor, boolean autoClose) {
        ArrayList<Task> tasks = new ArrayList<Task>();
        try {
            while (cursor.moveToNext()) {
                Task task = new Task();
                task.uuid = cursor.getString(0);
                task.task = cursor.getString(1);
                task.status = cursor.getInt(2);
                task.color = cursor.getString(3);
                task.priority = cursor.getInt(4);
                task.lastupdate = cursor.getString(5);
                tasks.add(task);
            }
        } catch (Exception e) {
            Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (autoClose) {
                cursor.close();
            }
        }
        return tasks;
    }

    /** 実行結果 */
    public enum RESULT {
        INSERTED, UPDATED, SKIPPED
    }

    public enum QUERY_OPTION {
        FORCE_UPDATE,
        WITHOUT_UPDATE_LASTUPDATE
    }
    /**
     * タスクの追加/更新
     * @param task タスク
     * @param options 実行オプション
     */
    public RESULT saveTask(Task task, QUERY_OPTION... options) {
        if (U.indexOf(QUERY_OPTION.WITHOUT_UPDATE_LASTUPDATE, options) < 0) {
            task.lastupdate = MyDate.now().format();
        }
        ContentValues values = new ContentValues();
        values.put("task", task.task);
        values.put("status", task.status);
        values.put("color", task.color);
        values.put("priority", task.priority);
        values.put("lastupdate", task.lastupdate);

        ////////////////////
        // UPDATE
        if (task.uuid != null) {
            String where;
            String[] params;
            if (U.indexOf(QUERY_OPTION.FORCE_UPDATE, options) < 0) {
                where = "uuid = ? and lastupdate < ?";
                params = strings(task.uuid, task.lastupdate);
            } else {
                where = "uuid = ?";
                params = strings(task.uuid);
            }
            int updateCount = db.update("tasks", values, where, params);
            if (updateCount != 0) {
                return RESULT.UPDATED;
            }
            Cursor cursor = db.rawQuery("select count(*) from tasks where uuid = ? ", strings(task.uuid));
            if (cursor.moveToLast() && cursor.getInt(0) > 0) {
                return RESULT.SKIPPED;
            }
        }

        ////////////////////
        // INSERT
        if (task.uuid == null) {
            task.uuid = UUID.randomUUID().toString();
        }
        values.put("uuid", task.uuid);
        db.insertOrThrow("tasks", null, values);
        return RESULT.INSERTED;
    }

    /**
     * ObjectのリストをStringの配列にして返す。
     * @param values 値
     * @return Stringの配列
     */
    public static String[] strings(Object... values) {
        List<String> list = new ArrayList<String>();
        for (Object value : values) {
            list.add(String.valueOf(value));
        }
        return list.toArray(new String[list.size()]);
    }

}