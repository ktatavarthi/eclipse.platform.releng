/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.releng.tools;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.team.core.RepositoryProvider;
import org.eclipse.team.internal.ccvs.core.CVSTag;
import org.eclipse.team.internal.ccvs.core.CVSTeamProvider;

public class MapProject implements IResourceChangeListener {
	
	private static MapProject mapProject = null;
	private IProject project;
	private MapFile[] mapFiles;
	
	/**
	 * Return the default map project (org.eclipse.releng) or
	 * <code>null</code> if the project does not exist or there
	 * is an error processing it. If there is an error, it
	 * will be logged.
	 * @return the default map project
	 */
	public static MapProject getDefaultMapProject(){		
		if (mapProject == null) {
			IProject project = getProjectFromWorkspace();
			try {
				mapProject = new MapProject(project);
			} catch (CoreException e) {
				RelEngPlugin.log(e);
			}
		}
		
		return mapProject;
	}
	
	private static IProject getProjectFromWorkspace() {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IWorkspaceRoot root = workspace.getRoot();
		IProject project = root.getProject(RelEngPlugin.MAP_PROJECT_NAME);
		return project;
	}

	public MapProject(IProject p) throws CoreException {
		this.project = p;
		ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
		loadMapFiles();
	}

	public IProject getProject() {
		return project;
	}
	
	public void setProject(IProject p){
		this.project = p;
	}
	
	private MapFile getMapFile(IProject p){
		for (int i = 0; i< mapFiles.length; i++){
			if (mapFiles[i].contains(p)) {
				return mapFiles[i];
			}
		}		
		return null;
	}
	
	/**
	 * Return the MapEntry for the given project
	 * @param string
	 * @param string1
	 */
	public MapEntry getMapEntry(IProject p) {
		MapFile file = getMapFile(p);
		if (file != null) {
			return file.getMapEntry(p);
		}
		return null;
	}

	public boolean mapsAreLoaded() {
		return project.exists();
	}
 
	public MapFile[] getValidMapFiles(){
		if(mapFiles == null || mapFiles.length == 0) return null;
		List list = new ArrayList();
		for (int i = 0; i <mapFiles.length; i++){
			IProject[] projects = mapFiles[i].getAccessibleProjects(); 
			if( projects!= null && projects.length > 0){
				list.add(mapFiles[i]);
			}
		}
		return (MapFile[])list.toArray(new MapFile[list.size()]);
	}
	
	/**
	 * @param aProject The map entry of the specified project will be changed to the specified tag
	 * @param tag The specified tag
	 * @return returns if no map file having such a map entry is found 
	 */
	public void updateFile(IProject aProject, String tag) throws CoreException {
		MapFile aFile = getMapFile(aProject);
		if (aFile == null)return;
		MapContentDocument changed = new MapContentDocument(aFile);
		changed.updateTag(aProject, tag);
		if (changed.isChanged()) {
			aFile.getFile().setContents(changed.getContents(), IFile.KEEP_HISTORY, null);
		}
	}
	
	public void commitMapProject(String comment, IProgressMonitor  monitor) throws CoreException{
		CVSTeamProvider provider = getProvider(project);
		provider.setComment(comment);		
		provider.checkin(new IResource[] { project }, IResource.DEPTH_INFINITE, monitor);
	}

	private  CVSTeamProvider getProvider(IResource resource) {
		return (CVSTeamProvider)RepositoryProvider.getProvider(resource.getProject());
	}
	
	public MapFile[] getMapFilesFor(IProject[] projects){
		Set alist = new HashSet();		
		for(int i = 0; i<projects.length; i++){
			MapFile aMapFile = getMapFile(projects[i]);
			alist.add(aMapFile);
		}
		return (MapFile[])alist.toArray(new MapFile[alist.size()]);
	}

	/**
	 * Get the tags for the given projects. If no tag is found
	 * for whatever reason, HEAD is used.
	 * @param projects
	 * @return
	 */
	public CVSTag[] getTagsFor(IProject[] projects){
		if(projects == null || projects.length == 0)return null;
		CVSTag[] tags = new CVSTag[projects.length];
		for (int i = 0; i < tags.length; i++){
			MapEntry entry = getMapEntry(projects[i]);
			if (entry == null) tags[i] = CVSTag.DEFAULT;
			else tags[i] = entry.getTag();		
		}
		return tags;
	}
	/**
	 * Deregister the IResourceChangeListner. It is reserved for use in the future. It is never called
	 * for now
	 */
	public void dispose(){
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
	}
	
	/**
	 * @see IResourceChangeListener#resourceChanged(IResourceChangeEvent)
	 */
	public void resourceChanged(IResourceChangeEvent event) {
		IResourceDelta root = event.getDelta();		
		IResourceDelta folderDelta = root.findMember(getMapFolder().getFullPath());
		if (folderDelta == null) return;
		IResourceDelta[] deltas = folderDelta.getAffectedChildren();
		if(deltas == null || deltas.length == 0) return;
		for (int i = 0; i < deltas.length; i++) {
			IResourceDelta delta = deltas[i];
			if(delta.getResource().getType() == IResource.FILE){				
				try{
					MapFile mFile = getMapFileFor((IFile)(delta.getResource()));	
					// Handle content change
					if(delta.getKind() == IResourceDelta.CHANGED){											
						
						mFile.loadEntries();	
					}
					// Handle deletion
					if(delta.getKind() == IResourceDelta.REMOVED ){
						removeMapFile(mFile);
					}
					// Handle addition
					if(delta.getKind() == IResourceDelta.ADDED ){
						addMapFile(mFile);
					}
				} catch (CoreException e) {
					RelEngPlugin.log(e);
				}
			}
		}
	}

	private IFolder getMapFolder(){
		return getProject().getFolder(RelEngPlugin.MAP_FOLDER);
	}
	private void loadMapFiles() throws CoreException {
		IFolder folder = project.getFolder(RelEngPlugin.MAP_FOLDER);
		if(!folder.exists()) return;
		IResource[] resource = folder.members();
		if (resource != null) {
			List list = new ArrayList();
			for (int i = 0; i < resource.length; i++) {
				//In case there are some sub folders
				if(resource[i].getType() == IResource.FILE){
					IFile file = (IFile) resource[i];
					String extension = file.getFileExtension();
					//In case file has no extension name or is not validate map file
					if( extension != null && extension.equals(MapFile.MAP_FILE_EXTENSION)){
						list.add(new MapFile(file));
					}
				}
			}
			mapFiles = (MapFile[])list.toArray(new MapFile[list.size()]);
		} else {
			mapFiles = new MapFile[0];
		}
	}
	
	private MapFile getMapFileFor(IFile file) throws CoreException{
		if(mapFiles == null || mapFiles.length == 0) return null;
		for(int i = 0; i < mapFiles.length; i++){
			if (mapFiles[i].getFile().equals(file))
				return mapFiles[i];
		}
		return new MapFile(file);
	}
	private void removeMapFile(MapFile aFile){
		ArrayList list = new ArrayList(Arrays.asList(mapFiles));
		if(list.contains(aFile)){
			if(list.size() <= 1){
				mapFiles = null;
			}else{
				list.remove(aFile);
				mapFiles = (MapFile[])list.toArray(new MapFile[list.size()]);
			}
		}	
	}
	private void addMapFile(MapFile aFile){
		if(mapFiles == null || mapFiles.length == 0) return;
		Set set = new HashSet(Arrays.asList(mapFiles));
		set.add(aFile);
		mapFiles = (MapFile[])set.toArray(new MapFile[set.size()]); 
	}
}
