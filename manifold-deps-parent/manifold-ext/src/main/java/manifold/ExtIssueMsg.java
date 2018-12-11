package manifold;

import manifold.util.IssueMsg;

/**
 */
public class ExtIssueMsg
{
  public static final IssueMsg MSG_THIS_FIRST = new IssueMsg( "'@This' must target only the first parameter of an extension method" );
  public static final IssueMsg MSG_EXPECTING_TYPE_FOR_THIS = new IssueMsg( "Expecting type '{0}' for '@This' parameter" );
  public static final IssueMsg MSG_MAYBE_MISSING_THIS = new IssueMsg( "Maybe missing '@This' to declare an instance extension method?" );
  public static final IssueMsg MSG_MUST_BE_STATIC = new IssueMsg( "Extension method '{0}' must be declared 'static'" );
  public static final IssueMsg MSG_MUST_NOT_BE_PRIVATE = new IssueMsg( "Extension method '{0}' must not be declared 'private'" );
  public static final IssueMsg MSG_EXTENSION_DUPLICATION = new IssueMsg( "Illegal extension method. '{0}' from '{1}' duplicates another extension method from '{2}'" );
  public static final IssueMsg MSG_EXTENSION_SHADOWS = new IssueMsg( "Illegal extension method. '{0}' from '{1}' duplicates a method in the extended class '{2}'" );
  public static final IssueMsg MSG_ONLY_STRUCTURAL_INTERFACE_ALLOWED_HERE = new IssueMsg( "Only structural interfaces allowed here, '{0}' is not structural" );
  public static final IssueMsg MSG_CANNOT_EXTEND_SOURCE_FILE = new IssueMsg( "Extending source file '{0}' in the same module, consider modifying the file directly." );
  public static final IssueMsg MSG_SELF_NOT_ALLOWED_HERE = new IssueMsg( "@Self is not allowed here, use it exclusively on instance method declaration return types." );
  public static final IssueMsg MSG_SELF_NOT_ON_CORRECT_TYPE = new IssueMsg( "@Self cannot be applied to the type '{0}', only on '{1}'." );
  public static final IssueMsg MSG_INCREMENT_OP_NOT_ALLOWED_REFLECTION = new IssueMsg( "++, -- expressions not supported with jailbreak, assign directly with '='" );
  public static final IssueMsg MSG_COMPOUND_OP_NOT_ALLOWED_REFLECTION = new IssueMsg( "Compound assignment operators not supported with jailbreak, assign directly with '='" );

  //
  // For now these are only used in the IntelliJ plugin
  //
  @SuppressWarnings("unused")
  public static final IssueMsg MSG_EXPECTING_EXTENSIONS_ROOT_PACKAGE = new IssueMsg( "Extension class must be rooted in 'extensions' package, found: '{0}'" );
  @SuppressWarnings("unused")
  public static final IssueMsg MSG_EXPECTING_EXTENDED_CLASS_NAME = new IssueMsg( "Expecting extended class name, found, '{0}' is not a class" );
  @SuppressWarnings("unused")
  public static final IssueMsg MSG_NOT_IN_EXTENSION_CLASS = new IssueMsg( "'{0}' must be used inside an extension class" );
}
