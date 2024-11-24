package slick.ast

/**
  * The type of a row lock
  */

abstract class RowLockType(val sqlName: String)

object RowLockType {
  case object ForUpdate extends RowLockType("for update")
  case object ForKeyShare extends RowLockType("for key share")
  case object ForShare extends RowLockType("for share")
  case object ForNoKeyUpdate extends RowLockType("for no key share")
}
