from kompile.interface.native.interface import PipelineRunner
import json
import torch
import numpy as np
from PIL import Image
import time

import torch
import torchvision
from torch.utils.data import Dataset, DataLoader
from torchvision import models, transforms
import torch.nn as nn
import torch.optim as optim
from torch.optim import lr_scheduler
import torch.nn.functional as F
from torch.autograd import Variable
import time

device = torch.device("cpu")
human_readable_labels = {
    'ageRange' : ['12-17', '18-23', '24-30', '31-40', '41-50', '51-60', '61+'],
    "gender": ["Female", "Male"],
    "race": ["Chinese", "Indian", "Malay", "Others"]
}
imsize = 224
loader = transforms.Compose([transforms.Resize((imsize, imsize)),
                             transforms.Grayscale(num_output_channels=3),
                             transforms.ToTensor(),
                             transforms.Normalize(mean = (0.4435,0.4435,0.4435), std=(0.2712,0.2712,0.2712)) ]) # stats from our data])



human_readable_labels = {
    'ageRange' : ['12-17', '18-23', '24-30', '31-40', '41-50', '51-60', '61+'],
    "gender": ["Female", "Male"],
    "race": ["Chinese", "Indian", "Malay", "Others"]
}

def predictFace(img,pipeline_runner):
    img = loader(img).float().numpy()  # convert to PIL image format
    img = np.expand_dims(img,axis=0)
    # Pre-process image
    input_arr_dict = {'input': img}
    print('About to call run')
    for i in range(0,5):
        start_time = time.time()
        output = pipeline_runner.run(input_arr_dict)
        print("Execution time is {} seconds".format(time.time() - start_time))
        print(output.keys())
    # Prediction to readable format
    #output,519,520 are the output names of the onnx model
    ageRange_pred = torch.argmax(torch.from_numpy(output[b'output']), dim=1)
    gender_pred = torch.argmax(torch.from_numpy(output[b'519']), dim=1)
    race_pred = torch.argmax(torch.from_numpy(output[b'520']), dim=1)
    print("Prediction: ", human_readable_labels["ageRange"][ageRange_pred.item()], "; ", human_readable_labels["gender"][gender_pred.item()], "; ", human_readable_labels["race"][race_pred.item()])
    label = human_readable_labels["ageRange"][ageRange_pred.item()], human_readable_labels["gender"][gender_pred.item()], human_readable_labels["race"][race_pred.item()]

    return label

with open('./vending-machine-pipeline-ndarray.json') as f:
    input_json = f.read()
    print(str(type(input_json)))
    pipeline_runner = PipelineRunner(pipeline_json=input_json)
    image = Image.open('faceDet.png')
    print('Running prediction')
    predictFace(image,pipeline_runner)
