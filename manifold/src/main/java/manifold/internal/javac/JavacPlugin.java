package manifold.internal.javac;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.ClassFinder;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.comp.Modules;
import com.sun.tools.javac.jvm.ClassWriter;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Collectors;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import manifold.internal.host.ManifoldHost;
import manifold.util.JavacDiagnostic;

/**
 */
public class JavacPlugin implements Plugin, TaskListener
{
  private static final String GOSU_SOURCE_FILES = "gosu.source.files";
  private static JavacPlugin INSTANCE;

  private Context _ctx;
  private JavaFileManager _fileManager;
  private JavacTaskImpl _javacTask;
  private Set<JavaFileObject> _javaInputFiles;
  private List<String> _gosuInputFiles;
  private TreeMaker _treeMaker;
  private JavacElements _javacElements;
  private TypeProcessor _typeProcessor;
  private IssueReporter _issueReporter;
  private ManifoldJavaFileManager _manFileManager;

  public static JavacPlugin instance()
  {
    return INSTANCE;
  }

  public JavacPlugin()
  {
    INSTANCE = this;
  }

  @Override
  public String getName()
  {
    return "Manifold";
  }

  @Override
  public void init( JavacTask task, String... args )
  {
    _javacTask = (JavacTaskImpl)task;
    _ctx = _javacTask.getContext();
    _fileManager = _ctx.get( JavaFileManager.class );
    //_javaInputFiles = fetchJavaInputFiles();
    _javaInputFiles = new HashSet<>();
    _gosuInputFiles = fetchGosuInputFiles();
    _treeMaker = TreeMaker.instance( _ctx );
    _javacElements = JavacElements.instance( _ctx );
    _typeProcessor = new TypeProcessor( task );
    _issueReporter = new IssueReporter(  Log.instance( getContext() ) );
    task.addTaskListener( this );
  }

  public Context getContext()
  {
    return _ctx;
  }

  public JavaFileManager getJavaFileManager()
  {
    return _fileManager;
  }

  public ManifoldJavaFileManager getManifoldFileManager()
  {
    return _manFileManager;
  }

  public JavacTaskImpl getJavacTask()
  {
    return _javacTask;
  }

  public Set<JavaFileObject> getJavaInputFiles()
  {
    return _javaInputFiles;
  }

  public List<String> getGosuInputFiles()
  {
    return _gosuInputFiles;
  }

  public TreeMaker getTreeMaker()
  {
    return _treeMaker;
  }

  public JavacElements getJavacElements()
  {
    return _javacElements;
  }

  public TypeProcessor getTypeProcessor()
  {
    return _typeProcessor;
  }

  public IssueReporter getIssueReporter()
  {
    return _issueReporter;
  }

  private void hijackJavacFileManager()
  {
    if( !(_fileManager instanceof ManifoldJavaFileManager) && _manFileManager == null )
    {
      injectManFileManager();
      ManifoldHost.initializeAndCompileNonJavaFiles( _fileManager, _gosuInputFiles, this::deriveSourcePath, this::deriveClasspath, this::deriveOutputPath );
    }
  }

  private void injectManFileManager()
  {
    _manFileManager = new ManifoldJavaFileManager( _fileManager, _ctx, true );
    _ctx.put( JavaFileManager.class, (JavaFileManager)null );
    _ctx.put( JavaFileManager.class, _manFileManager );
    try
    {
      Field field = ClassFinder.class.getDeclaredField( "fileManager" );
      field.setAccessible( true );
      field.set( ClassFinder.instance( getContext() ), _manFileManager );

      field = ClassFinder.class.getDeclaredField( "preferSource" );
      field.setAccessible( true );
      field.set( ClassFinder.instance( getContext() ), true );

      field = ClassWriter.class.getDeclaredField( "fileManager" );
      field.setAccessible( true );
      field.set( ClassWriter.instance( getContext() ), _manFileManager );
    }
    catch( Exception e )
    {
      throw new RuntimeException( e );
    }
  }

  private String deriveOutputPath()
  {
    try
    {
      String ping = "__dummy__";
      //JavaFileObject classFile = _jpe.getFiler().createClassFile( ping );
      JavaFileObject classFile = _fileManager.getJavaFileForOutput( StandardLocation.CLASS_OUTPUT, ping, JavaFileObject.Kind.CLASS, null );
      if( !isPhysicalFile( classFile ) )
      {
        return "";
      }
      File dummyFile = new File( classFile.toUri() );
      String path = dummyFile.getAbsolutePath();
      path = path.substring( 0, path.length() - (File.separatorChar + ping + ".class").length() );
      return path;
    }
    catch( IOException e )
    {
      throw new RuntimeException( e );
    }
  }

  private List<String> deriveClasspath()
  {
    URLClassLoader classLoader = (URLClassLoader)_javacTask.getContext().get( JavaFileManager.class ).getClassLoader( StandardLocation.CLASS_PATH );
    URL[] classpathUrls = classLoader.getURLs();
    List<String> paths = Arrays.stream( classpathUrls )
      .map( url ->
            {
              try
              {
                return new File( url.toURI() ).getAbsolutePath();
              }
              catch( URISyntaxException e )
              {
                throw new RuntimeException( e );
              }
            } ).collect( Collectors.toList() );
    return removeBadPaths( paths );
  }

  private List<String> removeBadPaths( List<String> paths )
  {
    // Remove a path that is a parent of another path.
    // For instance, "/foo/." is a parent of "/foo/classes", this must be unintentional

    List<String> actualPaths = new ArrayList<>();
    outer:
    for( String path : paths )
    {
      for( String p : paths )
      {
        //noinspection StringEquality
        String unmodifiedPath = path;
        if( path.endsWith( File.separator + '.' ) )
        {
          path = path.substring( 0, path.length() - 2 );
        }
        if( p != unmodifiedPath && p.startsWith( path ) )
        {
          continue outer;
        }
      }
      actualPaths.add( path );
    }
    return actualPaths;
  }

  private Set<String> deriveSourcePath()
  {
    Set<String> sourcePath = new HashSet<>();
    deriveSourcePath( _javaInputFiles, sourcePath );
    return sourcePath;
  }

  private void deriveSourcePath( Set<JavaFileObject> inputFiles, Set<String> sourcePath )
  {
    outer:
    for( JavaFileObject inputFile : inputFiles )
    {
      if( !isPhysicalFile( inputFile ) )
      {
        continue;
      }

      for( String sp : sourcePath )
      {
        if( inputFile.getName().startsWith( sp + File.separatorChar ) )
        {
          continue outer;
        }
      }
      String type = findType( inputFile );
      if( type != null )
      {
        String path = derivePath( type, inputFile );
        sourcePath.add( path );
      }
      else
      {

        getIssueReporter().report( new JavacDiagnostic( null, Diagnostic.Kind.ERROR, 0, 0, 0, "Could not find type for file: " + inputFile ) );
      }
    }
  }

  private boolean isPhysicalFile( JavaFileObject inputFile )
  {
    URI uri = inputFile.toUri();
    return uri != null && uri.getScheme().equalsIgnoreCase( "file" );
  }

  private String derivePath( String type, JavaFileObject inputFile )
  {
    String filename = inputFile.getName();
    int iDot = filename.lastIndexOf( '.' );
    String ext = iDot > 0 ? filename.substring( iDot ) : "";
    String pathRelativeFile = type.replace( '.', File.separatorChar ) + ext;
    assert filename.endsWith( pathRelativeFile );
    return filename.substring( 0, filename.indexOf( pathRelativeFile ) - 1 );
  }

  private String findType( JavaFileObject inputFile )
  {
    File file = new File( inputFile.toUri() );
    String name = file.getName();
    name = name.substring( 0, name.lastIndexOf( '.' ) );
    String packageName = Modules.instance( getContext() ).findPackageInFile.findPackageNameOf( inputFile ).toString();
    return packageName + '.' + name;

//    String path = inputFile.getName();
//    int dotJava = path.lastIndexOf( ".java" );
//    if( dotJava < 0 )
//    {
//      return null;
//    }
//    path = path.substring( 0, dotJava );
//    List<String> tokens = new ArrayList<>();
//    for( StringTokenizer tokenizer = new StringTokenizer( path, File.separator, false ); tokenizer.hasMoreTokens(); )
//    {
//      tokens.add( tokenizer.nextToken() );
//    }
//    String typeName = "";
//    TypeElement type = null;
//    for( int i = tokens.size() - 1; i >= 0; i-- )
//    {
//      typeName = tokens.get( i ) + typeName;
//      TypeElement csr = getType( typeName );
//      if( csr != null )
//      {
//        type = csr;
//      }
//      if( i > 0 )
//      {
//        typeName = '.' + typeName;
//      }
//    }
//    return type;
  }

//  private Set<JavaFileObject> fetchJavaInputFiles()
//  {
//    try
//    {
//      JavaCompiler javaCompiler = JavaCompiler.instance( _ctx );
//      Field field = javaCompiler.getClass().getDeclaredField( "inputFiles" );
//      field.setAccessible( true );
//      //noinspection unchecked
//      return (Set<JavaFileObject>)field.get( javaCompiler );
//    }
//    catch( Exception e )
//    {
//      throw new RuntimeException( e );
//    }
//  }

  private List<String> fetchGosuInputFiles()
  {
    String property = System.getProperty( GOSU_SOURCE_FILES, "" );
    if( !property.isEmpty() )
    {
      return Arrays.asList( property.split( " " ) );
    }
    return Collections.emptyList();
  }


  private TypeElement getType( String className )
  {
    return JavacElements.instance( getContext() ).getTypeElement( className );
    // DeclaredType declaredType = jpe.getTypeUtils().getDeclaredType( typeElement );
    // return new ElementTypePair( typeElement, declaredType );
  }

  @Override
  public void started( TaskEvent e )
  {
    switch( e.getKind() )
    {
      case PARSE:
        addSourceFile( e.getSourceFile() );
        break;
      case ENTER:
        hijackJavacFileManager();
        break;
      case ANALYZE:
        if( e.getSourceFile().getName().contains( "/List.java" ) )
        {
          System.out.println( "### List.java" );
        }
        break;
      case GENERATE:
        break;
      case ANNOTATION_PROCESSING:
        break;
      case ANNOTATION_PROCESSING_ROUND:
        break;
      case COMPILATION:
        break;
    }
    System.out.println( e );
  }

  private void addSourceFile( JavaFileObject sourceFile )
  {
    _javaInputFiles.add( sourceFile );
  }
}
