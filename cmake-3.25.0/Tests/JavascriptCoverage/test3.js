/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

var assert = require("assert")
var test  = {
  version: "1.0.0"
}
function covTest(p1,p2) {
  if (p1 > 3) {
    return 1;
  }
  else {
    return p1 + p2;
  }
}

function covTest2(p1,p2) {
  return 0;
}

function covTest3(p1) {
  for(i=0;i < p1;i++){
  }
  return i;
}
function covTest4(p1) {
  i=0;
  while(i < p1){
  i++;
  }
  return i;
}

describe('Array', function(){
  describe('CovTest2', function(){
    it('should return when the value is not present', function(){
      assert.equal(0,covTest2(2,2));
    })
  })
})
