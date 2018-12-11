package manifold.internal.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import manifold.api.host.IManifoldHost;
import manifold.util.JavacDiagnostic;

public abstract class CompiledTypeProcessor implements TaskListener
{
  private final IManifoldHost _host;
  private final BasicJavacTask _javacTask;
  private CompilationUnitTree _compilationUnit;
  private final Map<String, Boolean> _typesToProcess;
  private ParentMap _parents;
  private final IssueReporter<JavaFileObject> _issueReporter;
  private Map<String, JCTree.JCClassDecl> _innerClassForGeneration;
  private JCTree.JCClassDecl _tree;
  private boolean _generate;

  CompiledTypeProcessor( IManifoldHost host, BasicJavacTask javacTask )
  {
    _host = host;
    _javacTask = javacTask;
    javacTask.addTaskListener( this );
    _issueReporter = new IssueReporter<>( _javacTask::getContext );
    _typesToProcess = new HashMap<>();
    _innerClassForGeneration = new HashMap<>();
    _parents = new ParentMap();
  }

  /**
   * Subclasses override to process a compiled type.
   */
  protected abstract void process( TypeElement element, IssueReporter<JavaFileObject> issueReporter );

//  /**
//   * Subclasses override to filter javac compile errors / warnings.
//   */
//  public abstract boolean filterError( Diagnostic diagnostic );


  public Context getContext()
  {
    return ((BasicJavacTask)getJavacTask()).getContext();
  }

  public IManifoldHost getHost()
  {
    return _host;
  }

  public JavacTask getJavacTask()
  {
    return _javacTask;
  }

  public JCTree.JCClassDecl getTree()
  {
    return _tree;
  }

  public boolean isGenerate()
  {
    return _generate;
  }

  public CompilationUnitTree getCompilationUnit()
  {
    return _compilationUnit;
  }

  public Types getTypes()
  {
    return Types.instance( _javacTask.getContext() );
  }

  public JavacElements getElementUtil()
  {
    return JavacElements.instance( getContext() );
  }

  public Trees getTreeUtil()
  {
    return Trees.instance( getJavacTask() );
  }

  public TreeMaker getTreeMaker()
  {
    return TreeMaker.instance( getContext() );
  }

  public Symtab getSymtab()
  {
    return Symtab.instance( getContext() );
  }

  public Tree getParent( Tree node )
  {
    return _parents.getParent( node );
  }

  public JCTree.JCClassDecl getClassDecl( Tree node )
  {
    if( node == null || node instanceof JCTree.JCCompilationUnit )
    {
      return null;
    }

    if( node instanceof JCTree.JCClassDecl )
    {
      return (JCTree.JCClassDecl)node;
    }

    return getClassDecl( getParent( node ) );
  }

  public JavaFileObject getFile( Tree node )
  {
    JCTree.JCClassDecl classDecl = getClassDecl( node );
    return classDecl == null ? null : classDecl.sym.sourcefile;
  }

  public void report( JCTree tree, Diagnostic.Kind kind, String msg )
  {
    IssueReporter<JavaFileObject> reporter = new IssueReporter<>( _javacTask::getContext );
    JavaFileObject file = getFile( tree );
    reporter.report( new JavacDiagnostic( file, kind, tree.getStartPosition(), 0, 0, msg ) );
  }

  void addTypesToProcess( Set<String> types )
  {
    types.forEach( e -> _typesToProcess.put( e, false ) );
  }

  @Override
  public void started( TaskEvent e )
  {
    if( e.getKind() != TaskEvent.Kind.GENERATE )
    {
      return;
    }

    //
    // Process trees that were generated and therefore not available during ANALYZE
    // For instance, we must process bridge methods
    //

    TypeElement elem = e.getTypeElement();

    if( elem instanceof Symbol.ClassSymbol )
    {
      if( _typesToProcess.containsKey( elem.getQualifiedName().toString() ) )
      {
        _tree = findTopLevel( (Symbol.ClassSymbol)elem, e.getCompilationUnit().getTypeDecls() );
      }
      else
      {
        _tree = _innerClassForGeneration.get( ((Symbol.ClassSymbol)elem).flatName().toString() );
      }

      if( _tree != null )
      {
        _compilationUnit = e.getCompilationUnit();
        _generate = true;
        process( elem, _issueReporter );
      }
    }
  }

  @Override
  public void finished( TaskEvent e )
  {
    if( e.getKind() != TaskEvent.Kind.ANALYZE )
    {
      return;
    }

    //
    // Process fully analyzed trees (full type information is in the trees)
    //

    _generate = false;

    String fqn = e.getTypeElement().getQualifiedName().toString();
    Boolean visited = _typesToProcess.get( fqn );
    if( visited == Boolean.TRUE )
    {
      // already processed
      return;
    }
//    if( visited == null && !isNested( e.getTypeElement().getEnclosingElement() ) && !isOuter( fqn ) )
//    {
//      // also process inner types of types to process and (outer type if processing inner type first)
//      return;
//    }

    if( fqn.isEmpty() )
    {
      return;
    }
    
    // mark processed
    _typesToProcess.put( fqn, true );

    _compilationUnit = e.getCompilationUnit();

    TypeElement elem = e.getTypeElement();
    _tree = (JCTree.JCClassDecl)getTreeUtil().getTree( elem );
    preserveInnerClassesForGeneration( _tree );

    process( elem, _issueReporter );
  }

  private JCTree.JCClassDecl findTopLevel( Symbol.ClassSymbol type, List<? extends Tree> typeDecls )
  {
    for( Tree tree: typeDecls )
    {
      if( tree instanceof JCTree.JCClassDecl && ((JCTree.JCClassDecl)tree).sym == type )
      {
        return (JCTree.JCClassDecl)tree;
      }
    }
    return null;
  }

  private void preserveInnerClassesForGeneration( JCTree.JCClassDecl tree )
  {
    for( JCTree def: tree.defs )
    {
      if( def instanceof JCTree.JCClassDecl )
      {
        JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl)def;

        preserveInnerClassForGenerationPhase( classDecl );
        preserveInnerClassesForGeneration( classDecl );
      }
    }
  }

  public void preserveInnerClassForGenerationPhase( JCTree.JCClassDecl def )
  {
    _innerClassForGeneration.put( def.sym.flatName().toString(), def );
  }

  private class ParentMap
  {
    private Map<CompilationUnitTree, Map<Tree, Tree>> _parents;

    private ParentMap()
    {
      _parents = new HashMap<>();
    }

    private Tree getParent( Tree child )
    {
      Map<Tree, Tree> parents = _parents.computeIfAbsent( _compilationUnit, cu -> {
        Map<Tree, Tree> map = new HashMap<>();
        new ParentTreePathScanner( map ).scan( cu, null );
        return map;
      } );
      return parents.get( child );
    }
  }
}