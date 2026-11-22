package com.gari.reminder

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vehicles", indices = [Index(value = ["regNo"], unique = true)])
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val regNo: String,
    val clientName: String = "",
    val phone: String = "",
    val issueDate: String? = null,   // ISO yyyy-MM-dd
    val expDate: String,             // ISO yyyy-MM-dd
    val sentForExp: String? = null   // kon expDate er jonno SMS gese
)

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles ORDER BY expDate ASC")
    fun observeAll(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles")
    suspend fun all(): List<Vehicle>

    @Query("SELECT * FROM vehicles WHERE regNo = :reg LIMIT 1")
    suspend fun byReg(reg: String): Vehicle?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(v: Vehicle): Long

    @Delete
    suspend fun delete(v: Vehicle)
}

@Database(entities = [Vehicle::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): VehicleDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "gari.db")
                .build().also { inst = it }
        }
    }
}

object Prefs {
    const val DEFAULT_TEMPLATE =
        "প্রিয় {naam}, আপনার গাড়ি নং {gari_no} এর PUC এর মেয়াদ {last_date} তারিখে শেষ হবে। " +
            "অনুগ্রহ করে সময়মতো নবায়ন করুন। ধন্যবাদ।"

    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun template(c: Context): String = sp(c).getString("tpl", DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
    fun setTemplate(c: Context, t: String) = sp(c).edit().putString("tpl", t).apply()
    fun daysBefore(c: Context): Int = sp(c).getInt("days", 3)
}
