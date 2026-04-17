package top.yukonga.mishka.data.database

import androidx.room3.TypeConverter
import top.yukonga.mishka.data.model.ProfileType

class ProfileTypeConverter {
    @TypeConverter
    fun fromType(value: ProfileType): String = value.name

    @TypeConverter
    fun toType(value: String): ProfileType = ProfileType.fromStringOrDefault(value)
}
