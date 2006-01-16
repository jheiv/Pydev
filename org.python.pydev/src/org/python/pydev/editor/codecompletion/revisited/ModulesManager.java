/*
 * Created on May 24, 2005
 *
 * @author Fabio Zadrozny
 */
package org.python.pydev.editor.codecompletion.revisited;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.python.pydev.core.IModule;
import org.python.pydev.core.IModulesManager;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.ModulesKey;
import org.python.pydev.core.REF;
import org.python.pydev.editor.codecompletion.PyCodeCompletion;
import org.python.pydev.editor.codecompletion.revisited.modules.AbstractModule;
import org.python.pydev.editor.codecompletion.revisited.modules.CompiledModule;
import org.python.pydev.editor.codecompletion.revisited.modules.EmptyModule;
import org.python.pydev.editor.codecompletion.revisited.modules.SourceModule;
import org.python.pydev.plugin.PydevPlugin;

/**
 * @author Fabio Zadrozny
 */
public abstract class ModulesManager implements IModulesManager, Serializable {

    /**
     * Modules that we have in memory. This is persisted when saved.
     * 
     * Keys are ModulesKey with the name of the module. Values are AbstractModule objects.
     */
    protected transient Map<ModulesKey, AbstractModule> modules = new HashMap<ModulesKey, AbstractModule>();
    
    /**
     * This is the set of files that was found just right after unpickle (it should not be changed after that,
     * and serves only as a reference cache).
     */
    protected transient Set<File> files = new HashSet<File>();

    /**
     * Helper for using the pythonpath. Also persisted.
     */
    protected PythonPathHelper pythonPathHelper = new PythonPathHelper();

    private static final long serialVersionUID = 2L;

    /**
     * Custom deserialization is needed.
     */
    private void readObject(ObjectInputStream aStream) throws IOException, ClassNotFoundException {
        modules = new HashMap<ModulesKey, AbstractModule>();
        files = new HashSet<File>();
        aStream.defaultReadObject();
        Set set = (Set) aStream.readObject();
        for (Iterator iter = set.iterator(); iter.hasNext();) {
            ModulesKey key = (ModulesKey) iter.next();
            //restore with empty modules.
            modules.put(key, AbstractModule.createEmptyModule(key.name, key.file));
            if(key.file != null){
            	files.add(key.file);
            }
        }
    }

    /**
     * Custom serialization is needed.
     */
    private void writeObject(ObjectOutputStream aStream) throws IOException {
        aStream.defaultWriteObject();
        //write only the keys
        aStream.writeObject(new HashSet<ModulesKey>(this.modules.keySet()));
    }

    /**
     * @param modules The modules to set.
     */
    private void setModules(Map<ModulesKey, AbstractModule> modules) {
        this.modules = modules;
    }

    /**
     * @return Returns the modules.
     */
    protected Map<ModulesKey, AbstractModule> getModules() {
        return modules;
    }

    /**
     * Must be overriden so that the available builtins (forced or not) are returned.
     */
    public abstract String[] getBuiltins();

    
	public void validatePathInfo(String pythonpath, final IProject project, IProgressMonitor monitor) {
		//it is all comented because it could take quite some time to do that validation, so, we have to check 
		//for a better way to do it...

	    if(this.modules.size() < 10){
            //either there are not many modules (in which case, we could restore it without many problems)
            //or the data is not really valid (in which case, we must restore it).
    		List<String> lPythonpath = new ArrayList<String>();
    		List<File> completions = new ArrayList<File>();
    		List<String> fromJar = new ArrayList<String>();
    
    		int total = listFilesForCompletion(monitor, lPythonpath, completions, fromJar);
            changePythonPath(pythonpath, project, monitor, lPythonpath, completions, fromJar, total);
        }
//		
//		this.pythonPathHelper.getPythonPathFromStr(pythonpath, lPythonpath);
//		if(!this.pythonPathHelper.pythonpath.equals(lPythonpath)){
//			// the pythonpath is not the same
//			lPythonpath = pythonPathHelper.setPythonPath(pythonpath);
//			PydevPlugin.log(IStatus.INFO, "The pythonpath was not valid and will be restored.", null, true);
//			changePythonPath(pythonpath, project, monitor, lPythonpath, completions, fromJar, total);
//		
//		}else if(!validateFiles(completions)){
//			//the files are not valid
//			PydevPlugin.log(IStatus.INFO, "The pythonpath files were not valid and will be restored.", null, true);
//			changePythonPath(pythonpath, project, monitor, lPythonpath, completions, fromJar, total);
//		}
	}

	private boolean validateFiles(List<File> completions) {
		for (File f : completions) {
			if(!files.contains(f)){
				return false;
			}
		}
		return true;
	}

	/**
	 * 
	 * @param monitor this is the monitor
	 * @param pythonpathList this is the pythonpath
	 * @param completions OUT - the files that were gotten as valid python modules 
	 * @param fromJar OUT - the names of the modules that were found inside a jar
	 * @return the total number of modules found (that's completions + fromJar)
	 */
	private int listFilesForCompletion(IProgressMonitor monitor, List<String> pythonpathList, List<File> completions, List<String> fromJar) {
		int total = 0;
		//first thing: get all files available from the python path and sum them up.
        for (Iterator iter = pythonpathList.iterator(); iter.hasNext() && monitor.isCanceled() == false;) {
            String element = (String) iter.next();

            //the slow part is getting the files... not much we can do (I think).
            File root = new File(element);
            List<File>[] below = pythonPathHelper.getModulesBelow(root, monitor);
            if(below != null){
                completions.addAll(below[0]);
                total += below[0].size();
                
            }else{ //ok, it was null, so, maybe this is not a folder, but  zip file with java classes...
                List<String> currFromJar = PythonPathHelper.getFromJar(root, monitor);
                if(currFromJar != null){
                    fromJar.addAll(currFromJar);
                    total += currFromJar.size();
                }
            }
        }
		return total;
	}

	public void changePythonPath(String pythonpath, final IProject project, IProgressMonitor monitor) {
		List<String> pythonpathList = pythonPathHelper.setPythonPath(pythonpath);
		List<File> completions = new ArrayList<File>();
		List<String> fromJar = new ArrayList<String>();
		int total = listFilesForCompletion(monitor, pythonpathList, completions, fromJar);
		changePythonPath(pythonpath, project, monitor, pythonpathList, completions, fromJar, total);
	}
	
    /**
     * @param pythonpath string with the new pythonpath (separated by |)
     * @param project may be null if there is no associated project.
     */
    private void changePythonPath(String pythonpath, final IProject project, IProgressMonitor monitor, 
    		List<String> pythonpathList, List<File> completions, List<String> fromJar, int total) {

    	Map<ModulesKey, AbstractModule> mods = new HashMap<ModulesKey, AbstractModule>();
        int j = 0;

        //now, create in memory modules for all the loaded files (empty modules).
        for (Iterator iterator = completions.iterator(); iterator.hasNext() && monitor.isCanceled() == false; j++) {
            Object o = iterator.next();
            if (o instanceof File) {
                File f = (File) o;
                String fileAbsolutePath = REF.getFileAbsolutePath(f);
                String m = pythonPathHelper.resolveModule(fileAbsolutePath);

                monitor.setTaskName(new StringBuffer("Module resolved: ").append(j).append(" of ").append(total).append(" (").append(m)
                        .append(")").toString());
                monitor.worked(1);
                if (m != null) {
                    //we don't load them at this time.
                    ModulesKey modulesKey = new ModulesKey(m, f);
                    IModule module = mods.get(modulesKey);
                    
                    //ok, now, let's resolve any conflicts that we might find...
                    boolean add = false;
                    
                    //no conflict (easy)
                    if(module == null){
                        add = true;
                    }else{
                        //we have a conflict, so, let's resolve which one to keep (the old one or this one)
                        if(PythonPathHelper.isValidSourceFile(fileAbsolutePath)){
                            //source files have priority over other modules (dlls) -- if both are source, there is no real way to resolve
                            //this priority, so, let's just add it over.
                            add = true;
                        }
                    }
                    
                    if(add){
                        mods.put(modulesKey, AbstractModule.createEmptyModule(m, f));
                    }
                }
            }
        }
        
        for (String modName : fromJar) {
            mods.put(new ModulesKey(modName, null), AbstractModule.createEmptyModule(modName, null));
        }

        //create the builtin modules
        String[] builtins = getBuiltins();
        if(builtins != null){
	        for (int i = 0; i < builtins.length; i++) {
	            String name = builtins[i];
	            mods.put(new ModulesKey(name, null), AbstractModule.createEmptyModule(name, null));
	        }
        }

        //assign to instance variable
        this.setModules(mods);

    }


    /**
     * @see org.python.pydev.core.ICodeCompletionASTManager#rebuildModule(java.io.File, org.eclipse.jface.text.IDocument,
     *      org.eclipse.core.resources.IProject, org.eclipse.core.runtime.IProgressMonitor)
     */
    public void rebuildModule(File f, IDocument doc, final IProject project, IProgressMonitor monitor, IPythonNature nature) {
        final String m = pythonPathHelper.resolveModule(REF.getFileAbsolutePath(f));
        if (m != null) {
            //behaviour changed, now, only set it as an empty module (it will be parsed on demand)
            final ModulesKey key = new ModulesKey(m, f);
            doAddSingleModule(key, new EmptyModule(key.name, key.file));

            
        }else if (f != null){ //ok, remove the module that has a key with this file, as it can no longer be resolved
            Set<ModulesKey> toRemove = new HashSet<ModulesKey>();
            for (Iterator iter = getModules().keySet().iterator(); iter.hasNext();) {
                ModulesKey key = (ModulesKey) iter.next();
                if(key.file != null && key.file.equals(f)){
                    toRemove.add(key);
                }
            }
            removeThem(toRemove);
        }
    }


    /**
     * @see org.python.pydev.core.ICodeCompletionASTManager#removeModule(java.io.File, org.eclipse.core.resources.IProject,
     *      org.eclipse.core.runtime.IProgressMonitor)
     */
    public void removeModule(File file, IProject project, IProgressMonitor monitor) {
        if(file == null){
            return;
        }
        
        if (file.isDirectory()) {
            removeModulesBelow(file, project, monitor);

        } else {
            if(file.getName().startsWith("__init__.")){
                removeModulesBelow(file.getParentFile(), project, monitor);
            }else{
                removeModulesWithFile(file);
            }
        }
    }

    /**
     * @param file
     */
    private void removeModulesWithFile(File file) {
        if(file == null){
            return;
        }
        
        List<ModulesKey> toRem = new ArrayList<ModulesKey>();
        for (Iterator iter = getModules().keySet().iterator(); iter.hasNext();) {
            ModulesKey key = (ModulesKey) iter.next();
            if (key.file != null && key.file.equals(file)) {
                toRem.add(key);
            }
        }

        removeThem(toRem);
    }

    /**
     * removes all the modules that have the module starting with the name of the module from
     * the specified file.
     */
    private void removeModulesBelow(File file, IProject project, IProgressMonitor monitor) {
        if(file == null){
            return;
        }
        
        String absolutePath = REF.getFileAbsolutePath(file);
        List<ModulesKey> toRem = new ArrayList<ModulesKey>();
        
        for (Iterator iter = getModules().keySet().iterator(); iter.hasNext();) {
            ModulesKey key = (ModulesKey) iter.next();
            if (key.file != null && REF.getFileAbsolutePath(key.file).startsWith(absolutePath)) {
                toRem.add(key);
            }
        }

        removeThem(toRem);
    }


    /**
     * This method that actually removes some keys from the modules. 
     * 
     * @param toRem the modules to be removed
     */
    protected void removeThem(Collection<ModulesKey> toRem) {
        //really remove them here.
        for (Iterator<ModulesKey> iter = toRem.iterator(); iter.hasNext();) {
            doRemoveSingleModule(iter.next());
        }
    }

    /**
     * This is the only method that should remove a module.
     * No other method should remove them directly.
     * 
     * @param key this is the key that should be removed
     */
    protected void doRemoveSingleModule(ModulesKey key) {
        this.modules.remove(key);
    }

    /**
     * This is the only method that should add / update a module.
     * No other method should add it directly (unless it is loading or rebuilding it).
     * 
     * @param key this is the key that should be added
     * @param n 
     */
    protected void doAddSingleModule(final ModulesKey key, AbstractModule n) {
        this.modules.put(key, n);
    }

    /**
     * @return a set of all module keys
     */
    public Set<String> getAllModuleNames() {
        Set<String> s = new HashSet<String>();
        Set<ModulesKey> moduleKeys = getModules().keySet();
        for (ModulesKey key : moduleKeys) {
            s.add(key.name);
        }
        return s;
    }

    /**
     * @return a Set of strings with all the modules.
     */
    public ModulesKey[] getAllModules() {
        return (ModulesKey[]) getModules().keySet().toArray(new ModulesKey[0]);
    }
    
    public ModulesKey[] getOnlyDirectModules() {
        return getAllModules();
    }

    /**
     * @return
     */
    public int getSize() {
        return getModules().size();
    }

    /**
     * This method returns the module that corresponds to the path passed as a parameter.
     * 
     * @param name
     * @param dontSearchInit is used in a negative form because initially it was isLookingForRelative, but
     * it actually defines if we should look in __init__ modules too, so, the name matches the old signature.
     * 
     * NOTE: isLookingForRelative description was: when looking for relative imports, we don't check for __init__
     * @return the module represented by this name
     */
    public IModule getModule(String name, IPythonNature nature, boolean dontSearchInit) {
        AbstractModule n = null;
        
        //check for supported builtins these don't have files associated.
        //they are the first to be passed because the user can force a module to be builtin, because there
        //is some information that is only useful when you have builtins, such as os and wxPython (those can
        //be source modules, but they are so hacked that it is almost impossible to get useful information
        //from them).
        String[] builtins = getBuiltins();
        
        for (int i = 0; i < builtins.length; i++) {
            if (name.equals(builtins[i])) {
                n = (AbstractModule) getModules().get(new ModulesKey(name, null));
                if(n == null || n instanceof EmptyModule || n instanceof SourceModule){ //still not created or not defined as compiled module (as it should be)
                    n = new CompiledModule(name, PyCodeCompletion.TYPE_BUILTIN, nature.getAstManager());
                    doAddSingleModule(new ModulesKey(n.getName(), null), n);
                }
            }
        }


        if(n == null){
            if(!dontSearchInit){
                if(n == null){
                    n = (AbstractModule) getModules().get(new ModulesKey(name + ".__init__", null));
                    if(n != null){
                        name += ".__init__";
                    }
                }
            }
            if (n == null) {
            	n = (AbstractModule) getModules().get(new ModulesKey(name, null));
            }
        }

        if (n instanceof SourceModule) {
            //ok, module exists, let's check if it is synched with the filesystem version...
            SourceModule s = (SourceModule) n;
            if (!s.isSynched()) {
                //change it for an empty and proceed as usual.
                n = new EmptyModule(s.getName(), s.getFile());
                doAddSingleModule(new ModulesKey(s.getName(), s.getFile()), n);
            }
        }

        if (n instanceof EmptyModule) {
            EmptyModule e = (EmptyModule) n;

            //let's treat os as a special extension, since many things it has are too much
            //system dependent, and being so, many of its useful completions are not goten
            //e.g. os.path is defined correctly only on runtime.

            boolean found = false;

            if (!found && e.f != null) {
                try {
                    n = AbstractModule.createModule(name, e.f, nature, -1);
                } catch (FileNotFoundException exc) {
                    doRemoveSingleModule(new ModulesKey(name, e.f));
                    n = null;
                }

            }else{ //ok, it does not have a file associated, so, we treat it as a builtin (this can happen in java jars)
                n = new CompiledModule(name, PyCodeCompletion.TYPE_BUILTIN, nature.getAstManager());
            }
            
            if (n != null) {
                doAddSingleModule(new ModulesKey(name, e.f), n);
            } else {
                System.err.println("The module " + name + " could not be found nor created!");
            }
        }

        if (n instanceof EmptyModule) {
            throw new RuntimeException("Should not be an empty module anymore!");
        }
        return n;

    }


    /** 
     * @see org.python.pydev.core.IProjectModulesManager#isInPythonPath(org.eclipse.core.resources.IResource, org.eclipse.core.resources.IProject)
     */
    public boolean isInPythonPath(IResource member, IProject container) {
        return resolveModule(member, container) != null;
    }

    
    /** 
     * @see org.python.pydev.core.IProjectModulesManager#resolveModule(org.eclipse.core.resources.IResource, org.eclipse.core.resources.IProject)
     */
    public String resolveModule(IResource member, IProject container) {
        IPath location = PydevPlugin.getLocation(member.getFullPath(), container);
        if(location == null){
            //not in workspace?... maybe it was removed, so, do nothing, but let the user know about it
            PydevPlugin.log(getResolveModuleErr(member));
            return null;
        }else{
            File inOs = new File(location.toOSString());
            return resolveModule(REF.getFileAbsolutePath(inOs));
        }
    }

    protected String getResolveModuleErr(IResource member) {
		return "Unable to find the path "+member+" in the project were it\n" +
        "is added as a source folder for pydev."+this.getClass();
	}

	/**
     * @param full
     * @return
     */
    public String resolveModule(String full) {
        return pythonPathHelper.resolveModule(full, false);
    }

    public List<String> getPythonPath(){
        return new ArrayList<String>(pythonPathHelper.pythonpath);
    }
}
