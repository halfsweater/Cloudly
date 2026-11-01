package com.halfblanket.cloudly.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AccountEntity::class, TransferEntity::class, CachedListEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CloudlyDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun transfers(): TransferDao
    abstract fun listCache(): ListCacheDao

    companion object {
        fun create(context: Context): CloudlyDatabase =
            Room.databaseBuilder(context, CloudlyDatabase::class.java, "cloudly.db").build()
    }
}
