/*******************************************************************************
 * Copyright (c) 2017 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
_MF_CLS(_PFX_UTIL+"_Queue",_MF_OBJECT,{_q:null,_space:0,_size:-1,constructor_:function(){this._callSuper("constructor_");this._q=[];},length:function(){return this._q.length-this._space;},isEmpty:function(){return(this._q.length==0);},setQueueSize:function(A){this._size=A;this._readjust();},enqueue:function(A){this._q.push(A);this._readjust();},_readjust:function(){var A=this._size;while(A&&A>-1&&this.length()>A){this.dequeue();}},remove:function(B){var A=this.indexOf(B);if(A!=-1){this._q.splice(A,1);}},dequeue:function(){var B=null;var C=this._q.length;var A=this._q;if(C){B=A[this._space];if((++this._space)<<1>=C){this._q=A.slice(this._space);this._space=0;}}return B;},each:function(A){this._Lang.arrForEach(this._q,A,this._space);},arrFilter:function(A){return this._Lang.arrFilter(this._q,A,this._space);},indexOf:function(A){return this._Lang.arrIndexOf(this._q,A);},cleanup:function(){this._q=[];this._space=0;}});