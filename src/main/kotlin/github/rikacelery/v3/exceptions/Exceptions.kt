package github.rikacelery.v3.exceptions

class RenameException(val newName: String) : Exception("Room renamed to $newName")
class DeletedException : Exception("Room deleted")
