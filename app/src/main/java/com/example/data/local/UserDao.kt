package com.example.data.local

import kotlinx.coroutines.flow.Flow

interface UserDao {
    fun getUserFlow(userId: Int): Flow<UserEntity?>
    suspend fun getUser(userId: Int): UserEntity?
    suspend fun insertUser(user: UserEntity)
    suspend fun clearUser()
}
