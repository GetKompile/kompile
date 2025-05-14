/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.deps;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.function.Predicate;

public class DependencyMapIdentity<K,V> implements IDependencyMap<K,V> {
    //IDependeeGroup will act as dummy interface and will be ignored

    private IdentityHashMap<K, HashSet<V>> map = new IdentityHashMap<K, HashSet<V>>();  
    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public void add(K dependeeGroup, V element) {
      HashSet<V> s = map.get(dependeeGroup);
      if(s==null){
        s= new HashSet<V> ();
        map.put(dependeeGroup, s);
      }
       s.add(element);
    }

    @Override
    public Iterable<V> getDependantsForEach(K dependeeGroup) {
        return map.get(dependeeGroup);
    }

    @Override
    public Iterable<V> getDependantsForGroup(K dependeeGroup) {
        return map.get(dependeeGroup);
    }

    @Override
    public boolean containsAny(K dependeeGroup) {
        return map.containsKey(dependeeGroup);
    }

    @Override
    public boolean containsAnyForGroup(K dependeeGroup) {
        return map.containsKey(dependeeGroup);
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public void removeGroup(K dependeeGroup) {
        map.remove(dependeeGroup);
    }

    @Override
    public Iterable<V> removeGroupReturn(K dependeeGroup) {
        return map.remove(dependeeGroup);
    }

    @Override
    public void removeForEach(K dependeeGroup) {
          map.remove(dependeeGroup);
    }

    @Override
    public Iterable<V> removeForEachResult(K dependeeGroup) {
        return map.remove(dependeeGroup);
    }

    @Override
    public Iterable<V> removeGroupReturn(K dependeeGroup, Predicate<V> predicate) {
        HashSet<V> s= new HashSet<V> ();
        HashSet<V> ret = map.get(dependeeGroup);
        if(ret!=null){
            long prevSize = ret.size();
            for (V v : ret) {
                if(predicate.test(v)) s.add(v);
            }
            for (V v : s) {
                ret.remove(s);
            }
            //remove the key as well
            if(prevSize == s.size()){
                //remove the key
                //as we are testing containsAny using key
                map.remove(dependeeGroup);
            }
        }
        return s;
    }
    
}
